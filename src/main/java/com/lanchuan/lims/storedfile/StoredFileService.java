package com.lanchuan.lims.storedfile;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.CurrentActor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class StoredFileService {
    private static final Set<String> EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "bmp", "xlsx", "docx");
    private static final Set<String> MEDIA_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/bmp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final Path root;
    private final long maxSize;

    public StoredFileService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                             @Value("${lims.files.root}") Path root,
                             @Value("${lims.files.max-size-bytes}") long maxSize) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.root = root.toAbsolutePath().normalize();
        this.maxSize = maxSize;
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(maxSize);
        ZipSecureFile.setMaxTextSize(Math.min(maxSize, 10L * 1024 * 1024));
    }

    public record View(UUID id, String sha256, long sizeBytes, String mediaType, String originalName,
                       String purpose, OffsetDateTime uploadedAt) {}
    public record Download(View metadata, FileSystemResource resource) {}

    @Transactional
    public View storeGenerated(byte[] content, String originalName, String mediaType, String purpose, UUID uploadedBy) {
        if (content == null || content.length == 0 || content.length > maxSize) {
            throw invalid("GENERATED_FILE_SIZE_INVALID", "生成文件大小无效");
        }
        try {
            validateGenerated(content, mediaType);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            Path destination = destination(sha);
            Files.createDirectories(destination.getParent());
            if (!Files.exists(destination)) {
                Path tempRoot = root.resolve(".tmp");
                Files.createDirectories(tempRoot);
                Path temp = Files.createTempFile(tempRoot, "generated-", ".part");
                try {
                    Files.write(temp, content);
                    moveIfAbsent(temp, destination);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO stored_file(id, sha256, size_bytes, media_type, original_name,
                      storage_path, purpose, uploaded_by)
                    VALUES (:id, :sha, :size, :mediaType, :name, :path, :purpose, :actor)
                    """).param("id", id).param("sha", sha).param("size", content.length)
                    .param("mediaType", mediaType).param("name", safeName(originalName))
                    .param("path", root.relativize(destination).toString()).param("purpose", purpose)
                    .param("actor", uploadedBy).update();
            return require(id);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GENERATED_FILE_STORE_FAILED", "生成文件保存失败");
        }
    }

    private void validateGenerated(byte[] content, String mediaType) throws IOException {
        boolean pdf = mediaType.equals("application/pdf") && content.length >= 4
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
        if (pdf) return;
        boolean xlsx = mediaType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                && content.length >= 4 && content[0] == 'P' && content[1] == 'K';
        if (xlsx) {
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
                if (workbook.getNumberOfSheets() > 0) return;
            }
        }
        throw invalid("GENERATED_FILE_INVALID", "生成文件的格式或结构无效");
    }

    @Transactional
    public View store(MultipartFile upload, String purpose) {
        if (upload == null || upload.isEmpty()) throw invalid("FILE_EMPTY", "上传文件不能为空");
        if (upload.getSize() <= 0 || upload.getSize() > maxSize) throw invalid("FILE_SIZE_INVALID", "文件大小超出允许范围");
        String originalName = safeName(upload.getOriginalFilename());
        String extension = extension(originalName);
        String mediaType = upload.getContentType() == null ? "" : upload.getContentType().toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension) || !MEDIA_TYPES.contains(mediaType)) {
            throw invalid("FILE_TYPE_REJECTED", "仅允许受控 PDF、图片、XLSX 和 DOCX 文件");
        }
        try {
            Path tempRoot = root.resolve(".tmp");
            Files.createDirectories(tempRoot);
            Path temp = Files.createTempFile(tempRoot, "upload-", ".part");
            String sha;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new DigestInputStream(new BufferedInputStream(upload.getInputStream()), digest)) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                sha = HexFormat.of().formatHex(digest.digest());
                validateContent(temp, extension);
                Path destination = destination(sha);
                Files.createDirectories(destination.getParent());
                if (!Files.exists(destination)) moveIfAbsent(temp, destination);
                else Files.deleteIfExists(temp);
                UUID id = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO stored_file(id, sha256, size_bytes, media_type, original_name,
                          storage_path, purpose, uploaded_by)
                        VALUES (:id, :sha, :size, :mediaType, :name, :path, :purpose, :actor)
                        """).param("id", id).param("sha", sha).param("size", upload.getSize())
                        .param("mediaType", mediaType).param("name", originalName)
                        .param("path", root.relativize(destination).toString()).param("purpose", purpose)
                        .param("actor", actor.require().id()).update();
                View stored = require(id);
                audit.record("FILE_STORED", "STORED_FILE", id, null, stored, null, null);
                return stored;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_FAILED", "文件保存失败");
        }
    }

    public View require(UUID id) {
        return jdbc.sql("""
                SELECT id, sha256, size_bytes, media_type, original_name, purpose, uploaded_at
                FROM stored_file WHERE id=:id
                """).param("id", id).query((rs, row) -> new View(rs.getObject("id", UUID.class),
                        rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("media_type"),
                        rs.getString("original_name"), rs.getString("purpose"),
                        rs.getObject("uploaded_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "文件不存在"));
    }

    public View requireCurrentUpload(UUID id) {
        View file = require(id);
        boolean owned = jdbc.sql("SELECT uploaded_by=:actor FROM stored_file WHERE id=:id")
                .param("actor", actor.require().id()).param("id", id).query(Boolean.class).single();
        if (!owned) throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不属于当前上传人");
        return file;
    }

    @Transactional
    public Download download(UUID id, String downloadToken) {
        View metadata = require(id);
        enforcePersonnelAttachmentScope(id);
        enforceOrganizationAttachmentScope(id);
        if (Set.of("COMMISSION_EXPORT", "FINANCE_EXPORT", "QUALITY_EXPORT", "PERSON_EXPORT").contains(metadata.purpose())) {
            boolean owner = jdbc.sql("SELECT uploaded_by=:actor FROM stored_file WHERE id=:id")
                    .param("actor", actor.require().id()).param("id", id).query(Boolean.class).single();
            if (!owner && !actor.require().permissions().contains("SYSTEM_ADMIN")) {
                throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不在当前下载范围内");
            }
        }
        boolean reportPdf = jdbc.sql("SELECT EXISTS(SELECT 1 FROM report_version WHERE pdf_file_id=:file)")
                .param("file", id).query(Boolean.class).single();
        if (reportPdf) consumeReportAuthorization(id, downloadToken);
        String relative = jdbc.sql("SELECT storage_path FROM stored_file WHERE id=:id")
                .param("id", id).query(String.class).single();
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw ApiException.notFound("FILE_CONTENT_MISSING", "文件内容不存在");
        }
        audit.record("FILE_DOWNLOADED", "STORED_FILE", id, null, metadata, null, null);
        return new Download(metadata, new FileSystemResource(path));
    }

    private void enforcePersonnelAttachmentScope(UUID fileId) {
        boolean personnelFile = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person WHERE avatar_file_id=:file OR id_front_file_id=:file
                  OR id_back_file_id=:file OR social_security_file_id=:file)
                  OR EXISTS(SELECT 1 FROM person_qualification WHERE certificate_file_id=:file)
                  OR EXISTS(SELECT 1 FROM training_record WHERE certificate_file_id=:file)
                  OR EXISTS(SELECT 1 FROM person_title_record WHERE attachment_file_id=:file)
                """).param("file", fileId).query(Boolean.class).single();
        if (!personnelFile) return;
        var user = actor.require();
        boolean accessible = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person p JOIN department d ON d.id=p.department_id
                  WHERE d.organization_id=:organization AND (p.avatar_file_id=:file OR p.id_front_file_id=:file
                    OR p.id_back_file_id=:file OR p.social_security_file_id=:file)
                    AND (:scope IN ('ALL','ORGANIZATION')
                      OR (:scope='DEPARTMENT' AND p.department_id=:department)
                      OR (:scope IN ('SELF','PROJECT') AND p.id=:person)))
                  OR EXISTS(SELECT 1 FROM person_qualification q JOIN person p ON p.id=q.person_id
                    JOIN department d ON d.id=p.department_id
                    WHERE d.organization_id=:organization AND q.certificate_file_id=:file
                      AND (:scope IN ('ALL','ORGANIZATION')
                        OR (:scope='DEPARTMENT' AND p.department_id=:department)
                        OR (:scope IN ('SELF','PROJECT') AND p.id=:person)))
                  OR EXISTS(SELECT 1 FROM training_record t JOIN person p ON p.id=t.person_id
                    JOIN department d ON d.id=p.department_id
                    WHERE d.organization_id=:organization AND t.certificate_file_id=:file
                      AND (:scope IN ('ALL','ORGANIZATION')
                        OR (:scope='DEPARTMENT' AND p.department_id=:department)
                        OR (:scope IN ('SELF','PROJECT') AND p.id=:person)))
                  OR EXISTS(SELECT 1 FROM person_title_record t JOIN person p ON p.id=t.person_id
                    JOIN department d ON d.id=p.department_id
                    WHERE d.organization_id=:organization AND t.attachment_file_id=:file
                      AND (:scope IN ('ALL','ORGANIZATION')
                        OR (:scope='DEPARTMENT' AND p.department_id=:department)
                        OR (:scope IN ('SELF','PROJECT') AND p.id=:person)))
                """).param("organization", user.organizationId()).param("scope", user.dataScope())
                .param("department", user.departmentId() == null ? new UUID(0, 0) : user.departmentId())
                .param("person", user.personId() == null ? new UUID(0, 0) : user.personId()).param("file", fileId)
                .query(Boolean.class).single();
        if (!accessible) throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不在当前机构范围内");
        boolean sensitivePersonnelFile = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person WHERE id_front_file_id=:file
                  OR id_back_file_id=:file OR social_security_file_id=:file)
                """).param("file", fileId).query(Boolean.class).single();
        if (sensitivePersonnelFile && !user.permissions().contains("PERSON_SENSITIVE_READ")) {
            throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在或无权读取人员敏感资料");
        }
    }

    private void enforceOrganizationAttachmentScope(UUID fileId) {
        boolean organizationFile = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM organization WHERE business_license_file_id=:file)
                  OR EXISTS(SELECT 1 FROM organization_qualification_record
                    WHERE original_file_id=:file OR copy_file_id=:file)
                  OR EXISTS(SELECT 1 FROM metrology_qualification_record
                    WHERE certificate_file_id=:file OR other_file_id=:file)
                """).param("file", fileId).query(Boolean.class).single();
        if (!organizationFile) return;
        boolean accessible = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM organization WHERE id=:organization AND business_license_file_id=:file)
                  OR EXISTS(SELECT 1 FROM organization_qualification_record
                    WHERE organization_id=:organization AND (original_file_id=:file OR copy_file_id=:file))
                  OR EXISTS(SELECT 1 FROM metrology_qualification_record
                    WHERE organization_id=:organization AND (certificate_file_id=:file OR other_file_id=:file))
                """).param("organization", actor.require().organizationId()).param("file", fileId)
                .query(Boolean.class).single();
        if (!accessible) throw ApiException.notFound("FILE_NOT_FOUND", "文件不存在或不在当前机构范围内");
    }

    private void consumeReportAuthorization(UUID fileId, String token) {
        if (token == null || token.isBlank()) throw new ApiException(HttpStatus.FORBIDDEN,
                "REPORT_PRINT_AUTHORIZATION_REQUIRED", "报告 PDF 必须通过受控打印入口下载");
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        ReportAuthorization authorization = jdbc.sql("""
                UPDATE report_print_authorization SET consumed_at=now()
                WHERE token_hash=:token AND file_id=:file AND authorized_by=:actor
                  AND consumed_at IS NULL AND expires_at>now()
                RETURNING id,report_id,report_version_id
                """).param("token", hash).param("file", fileId).param("actor", actor.require().id())
                .query((rs, row) -> new ReportAuthorization(rs.getObject("id", UUID.class),
                        rs.getObject("report_id", UUID.class), rs.getObject("report_version_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "REPORT_PRINT_AUTHORIZATION_INVALID", "报告下载授权无效、已使用或已过期"));
        audit.record("REPORT_PDF_DOWNLOADED", "REPORT", authorization.reportId(), null,
                java.util.Map.of("authorizationId", authorization.id(),
                        "reportVersionId", authorization.reportVersionId(), "fileId", fileId), null,
                "一次性受控下载授权已消费");
    }

    private void validateContent(Path path, String extension) throws IOException {
        byte[] header = new byte[8];
        try (InputStream input = Files.newInputStream(path)) {
            int read = input.read(header);
            if (read < 4) throw invalid("FILE_HEADER_INVALID", "文件头无效");
        }
        boolean valid = switch (extension) {
            case "pdf" -> header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F'
                    && PdfUploadValidator.isSafe(path);
            case "png" -> (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
            case "jpg", "jpeg" -> (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8;
            case "bmp" -> header[0] == 'B' && header[1] == 'M';
            case "xlsx", "docx" -> header[0] == 'P' && header[1] == 'K' && validateOfficePackage(path, extension);
            default -> false;
        };
        if (!valid) throw invalid("FILE_HEADER_MISMATCH", "文件内容与扩展名不匹配");
    }

    private boolean validateOfficePackage(Path path, String extension) throws IOException {
        boolean contentTypes = false;
        boolean expectedRoot = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000) throw invalid("OFFICE_PACKAGE_INVALID", "Office 文件条目过多");
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("[content_types].xml")) contentTypes = true;
                if (extension.equals("xlsx") && name.equals("xl/workbook.xml")) expectedRoot = true;
                if (extension.equals("docx") && name.equals("word/document.xml")) expectedRoot = true;
                if (name.endsWith("vbaproject.bin") || name.endsWith(".exe") || name.endsWith(".js")
                        || name.contains("../") || name.startsWith("/")) {
                    throw invalid("ACTIVE_CONTENT_REJECTED", "文件包含宏、可执行内容或不安全路径");
                }
            }
        }
        return contentTypes && expectedRoot;
    }

    private Path destination(String sha) { return root.resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha); }

    private void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private void moveIfAbsent(Path source, Path target) throws IOException {
        try { moveAtomically(source, target); }
        catch (FileAlreadyExistsException ignored) { Files.deleteIfExists(source); }
    }

    private String safeName(String value) {
        String normalized = value == null ? "upload" : value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank()) throw invalid("FILE_NAME_INVALID", "文件名无效");
        if (name.length() > 255 || name.contains("\u0000")) throw invalid("FILE_NAME_INVALID", "文件名无效");
        return name;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private record ReportAuthorization(UUID id, UUID reportId, UUID reportVersionId) {}
}
