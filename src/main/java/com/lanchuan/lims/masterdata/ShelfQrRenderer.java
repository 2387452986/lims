package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.common.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ShelfQrRenderer {
    // UUID payloads can require enough modules that a 180 px bitmap lands on
    // uneven module boundaries and becomes intermittently unreadable after PNG
    // encoding. 256 px keeps the generated shelf labels compact while giving
    // scanners a stable integer-sized quiet zone/module rendering.
    private static final int IMAGE_SIZE = 256;
    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 2);

    byte[] png(UUID shelfId) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    shelfId.toString(), BarcodeFormat.QR_CODE, IMAGE_SIZE, IMAGE_SIZE, HINTS);
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG writer unavailable");
                return output.toByteArray();
            }
        } catch (WriterException | IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SHELF_QR_RENDER_FAILED", "货架二维码生成失败");
        }
    }

    byte[] zip(List<FoundationController.ShelfView> shelves) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Set<String> filenames = new HashSet<>();
            for (FoundationController.ShelfView shelf : shelves) {
                String base = safeFilename(shelf.shelfNumber());
                String filename = base + ".png";
                if (!filenames.add(filename)) {
                    filename = base + "-" + shelf.id().toString().substring(0, 8) + ".png";
                    filenames.add(filename);
                }
                ZipEntry entry = new ZipEntry(filename);
                zip.putNextEntry(entry);
                zip.write(png(shelf.id()));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SHELF_QR_ARCHIVE_FAILED", "货架二维码压缩包生成失败");
        }
    }

    private String safeFilename(String shelfNumber) {
        String safe = shelfNumber.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return safe.substring(0, Math.min(safe.length(), 80));
    }
}
