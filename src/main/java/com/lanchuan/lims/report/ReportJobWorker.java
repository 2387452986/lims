package com.lanchuan.lims.report;

import com.lanchuan.lims.storedfile.StoredFileService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ReportJobWorker {
    private static final Logger log = LoggerFactory.getLogger(ReportJobWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final String workerId;
    private final String executable;
    private final SigningAssetService signingAssets;
    private final SignatureSchemeService signatureSchemes;

    public ReportJobWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                           StoredFileService files, SigningAssetService signingAssets,
                           SignatureSchemeService signatureSchemes,
                           @Value("${lims.reports.worker-id}") String workerId,
                           @Value("${lims.reports.chromium-executable:}") String executable) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.files = files;
        this.signingAssets = signingAssets;
        this.signatureSchemes = signatureSchemes;
        this.workerId = workerId;
        this.executable = executable;
    }

    @Scheduled(fixedDelayString = "${lims.reports.poll-delay-ms:3000}")
    void poll() {
        Job job = transactions.execute(status -> claim());
        if (job == null) return;
        try {
            render(job);
            transactions.executeWithoutResult(status -> complete(job.id()));
        } catch (Exception exception) {
            log.error("Report PDF job {} failed", job.id(), exception);
            transactions.executeWithoutResult(status -> fail(job.id(), exception));
        }
    }

    private Job claim() {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type='GENERATE_REPORT_PDF'
                    AND state IN ('READY','RETRY') AND available_at<=now()
                  ORDER BY available_at, created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_job j SET state='RUNNING', locked_by=:worker, locked_at=now(),
                  attempt_count=attempt_count+1 FROM candidate c WHERE j.id=c.id
                RETURNING j.id, j.payload::text, j.attempt_count
                """).param("worker", workerId).query((rs, row) -> new Job(rs.getObject("id", UUID.class),
                        rs.getString("payload"), rs.getInt("attempt_count"))).optional().orElse(null);
    }

    private void render(Job job) throws JacksonException {
        Map<String, Object> payload = json.readValue(job.payload(), new TypeReference<>() {});
        UUID versionId = UUID.fromString(String.valueOf(payload.get("reportVersionId")));
        boolean finalDocument = Boolean.TRUE.equals(payload.get("finalDocument"));
        ReportData report = jdbc.sql("""
                SELECT r.number, r.title, v.revision_label, v.content_json::text, r.created_by, c.organization_id,
                  v.signature_scheme_id,
                  (SELECT a.signing_asset_id FROM report_approval a WHERE a.report_version_id=v.id
                    AND a.step='ISSUE' AND a.decision='APPROVED' ORDER BY a.decided_at DESC LIMIT 1) signature_asset_id,
                  (SELECT a.signing_asset_id FROM report_approval a WHERE a.report_version_id=v.id
                    AND a.step='SEAL' AND a.decision='APPROVED' ORDER BY a.decided_at DESC LIMIT 1) seal_asset_id
                FROM report_version v JOIN report r ON r.id=v.report_id
                JOIN commission c ON c.id=r.commission_id WHERE v.id=:id
                """).param("id", versionId).query((rs, row) -> new ReportData(rs.getString("number"),
                        rs.getString("title"), rs.getString("revision_label"), rs.getString("content_json"),
                        rs.getObject("created_by", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("signature_scheme_id", UUID.class),
                        rs.getObject("signature_asset_id", UUID.class), rs.getObject("seal_asset_id", UUID.class))).single();
        Map<String, Object> content = json.readValue(report.content(), new TypeReference<>() {});
        SigningAssetService.AssetContent signature = finalDocument ? signingAssets.decrypt(report.signatureAssetId()) : null;
        SigningAssetService.AssetContent seal = finalDocument ? signingAssets.decrypt(report.sealAssetId()) : null;
        List<RenderedPosition> positions = finalDocument ? signatureSchemes.renderPositions(report.signatureSchemeId()).stream()
                .map(position -> new RenderedPosition(position, signingAssets.decrypt(position.signingAssetId())))
                .filter(position -> position.content() != null).toList() : List.of();
        byte[] pdf;
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox", "--disable-gpu"));
            if (executable != null && !executable.isBlank()) launch.setExecutablePath(java.nio.file.Path.of(executable));
            try (Browser browser = playwright.chromium().launch(launch)) {
                Page page = browser.newPage();
                page.setContent(html(report, content, positions.isEmpty() ? signature : null, positions.isEmpty() ? seal : null),
                        new Page.SetContentOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD));
                pdf = page.pdf(new Page.PdfOptions().setFormat("A4").setPrintBackground(true)
                        .setMargin(new Margin().setTop("16mm").setRight("15mm").setBottom("18mm").setLeft("15mm")));
            }
        }
        if (!positions.isEmpty()) pdf = applyScheme(pdf, positions);
        StoredFileService.View stored = files.storeGenerated(pdf,
                report.number() + "-" + report.revisionLabel() + ".pdf", "application/pdf", "REPORT_PDF", report.createdBy());
        int attached = finalDocument
                ? jdbc.sql("UPDATE report_version SET pdf_file_id=:file, final_pdf_at=now() WHERE id=:version AND sealed_at IS NOT NULL")
                    .param("file", stored.id()).param("version", versionId).update()
                : jdbc.sql("UPDATE report_version SET pdf_file_id=:file WHERE id=:version AND signed_at IS NULL")
                    .param("file", stored.id()).param("version", versionId).update();
        if (attached == 0) throw new IllegalStateException("报告版本状态已变化，PDF 不能附加");
        jdbc.sql("""
                INSERT INTO audit_event(id, request_id, actor_name, action, resource_type, resource_id, organization_id, after_json)
                VALUES (:id, :requestId, 'PDF作业', 'REPORT_PDF_GENERATED', 'REPORT_VERSION', CAST(:resource AS text), :organization,
                  jsonb_build_object('fileId', CAST(:file AS text), 'sha256', :sha, 'finalDocument', :finalDocument))
                """).param("id", UUID.randomUUID()).param("requestId", "job:" + job.id())
                .param("resource", versionId).param("organization", report.organizationId())
                .param("file", stored.id()).param("sha", stored.sha256())
                .param("finalDocument", finalDocument).update();
    }

    private void complete(UUID id) {
        int changed = jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',completed_at=now(),last_error=NULL,
                  locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("id", id).param("worker", workerId).update();
        if (changed != 1) throw new IllegalStateException("report job ownership changed before completion");
    }

    private void fail(UUID id, Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
        if (message.length() > 1900) message = message.substring(0, 1900);
        jdbc.sql("""
                UPDATE background_job SET state=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'RETRY' END,
                  available_at=now()+(attempt_count*interval '1 minute'), last_error=:error,
                  locked_by=NULL, locked_at=NULL WHERE id=:id AND locked_by=:worker
                """).param("error", message).param("id", id).param("worker", workerId).update();
    }

    private String html(ReportData report, Map<String, Object> content,
                        SigningAssetService.AssetContent signature, SigningAssetService.AssetContent seal) {
        StringBuilder rows = new StringBuilder();
        content.forEach((key, value) -> rows.append("<tr><th>").append(escape(key)).append("</th><td>")
                .append(escape(String.valueOf(value))).append("</td></tr>"));
        String signatures = signingHtml(signature, seal);
        return """
                <!doctype html><html><head><meta charset="utf-8"><style>
                @page{size:A4;margin:0}*{box-sizing:border-box}body{font-family:'Noto Sans CJK SC',sans-serif;color:#111;font-size:12px}
                header{text-align:center;border-bottom:2px solid #111;padding:8px 0 16px}h1{font-size:22px;margin:8px 0}header p{margin:4px}
                table{width:100%%;border-collapse:collapse;margin-top:18px}th,td{border:1px solid #555;padding:9px;vertical-align:top}th{width:28%%;background:#f3f5f7;text-align:left}
                .signatures{display:flex;justify-content:flex-end;gap:36px;min-height:84px;margin-top:22px}.signatures figure{width:120px;margin:0;text-align:center}.signatures img{display:block;max-width:112px;max-height:62px;margin:0 auto 5px;object-fit:contain}.signatures figcaption{font-size:10px;color:#555}
                footer{margin-top:28px;border-top:1px solid #777;padding-top:10px;color:#555;display:flex;justify-content:space-between}
                </style></head><body><header><p>澜川智检科技有限公司</p><h1>%s</h1><p>报告编号：%s　版本：%s</p></header>
                <table>%s</table>%s<footer><span>内部受控文件</span><span>由试验检测信息管理系统生成</span></footer></body></html>
                """.formatted(escape(report.title()), escape(report.number()), escape(report.revisionLabel()), rows, signatures);
    }

    private String signingHtml(SigningAssetService.AssetContent signature, SigningAssetService.AssetContent seal) {
        if (signature == null && seal == null) return "";
        StringBuilder html = new StringBuilder("<section class=\"signatures\">");
        if (signature != null) html.append(assetFigure(signature, "签发人受控签名"));
        if (seal != null) html.append(assetFigure(seal, "机构受控印章"));
        return html.append("</section>").toString();
    }

    private String assetFigure(SigningAssetService.AssetContent asset, String caption) {
        return "<figure><img src=\"data:" + asset.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(asset.content()) + "\"><figcaption>"
                + escape(caption) + "</figcaption></figure>";
    }

    static byte[] applyScheme(byte[] source, List<RenderedPosition> positions) {
        try (PDDocument document = Loader.loadPDF(source); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int last = document.getNumberOfPages() - 1;
            for (RenderedPosition rendered : positions) {
                SignatureSchemeService.RenderPosition position = rendered.position();
                List<Integer> pages = switch (position.pageRule()) {
                    case "FIRST" -> List.of(0);
                    case "LAST" -> List.of(last);
                    case "EVERY" -> java.util.stream.IntStream.rangeClosed(0, last).boxed().toList();
                    default -> throw new IllegalArgumentException("Unsupported signature page rule");
                };
                PDImageXObject image = PDImageXObject.createFromByteArray(document, rendered.content().content(), position.label());
                for (int pageIndex : pages) {
                    PDPage page = document.getPage(pageIndex);
                    float x = mm(15f + position.xMm().floatValue());
                    float width = mm(position.widthMm().floatValue());
                    float height = mm(position.heightMm().floatValue());
                    float y = page.getMediaBox().getHeight() - mm(16f + position.yMm().floatValue()) - height;
                    try (PDPageContentStream content = new PDPageContentStream(document, page,
                            PDPageContentStream.AppendMode.APPEND, true, true)) {
                        content.drawImage(image, x, y, width, height);
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("签章方案无法应用到最终 PDF", exception);
        }
    }

    private static float mm(float value) { return value * 72f / 25.4f; }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record Job(UUID id, String payload, int attempt) {}
    private record ReportData(String number, String title, String revisionLabel, String content,
                              UUID createdBy, UUID organizationId, UUID signatureSchemeId,
                              UUID signatureAssetId, UUID sealAssetId) {}
    record RenderedPosition(SignatureSchemeService.RenderPosition position,
                            SigningAssetService.AssetContent content) {}
}
