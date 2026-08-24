package com.lanchuan.lims.report;

import com.lanchuan.lims.common.ApiException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

final class SigningAssetImageValidator {
    private static final int MAX_DIMENSION = 4096;

    private SigningAssetImageValidator() {}

    record Dimensions(int width, int height) {}

    static Dimensions validate(byte[] content, String mediaType) {
        String expected = switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpeg";
            default -> throw invalid("签名或印章素材仅允许 PNG/JPEG");
        };
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw invalid("无法解析签名或印章图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("签名或印章图片内容无效");
            ImageReader reader = readers.next();
            try {
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!actual.equals(expected) && !(expected.equals("jpeg") && actual.equals("jpg"))) {
                    throw invalid("图片内容与声明格式不一致");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw invalid("签名或印章图片尺寸须在 1×1 至 4096×4096 像素之间");
                }
                if (reader.read(0) == null) throw invalid("签名或印章图片内容无效");
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw invalid("签名或印章图片内容无效");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_IMAGE_INVALID", message);
    }
}
