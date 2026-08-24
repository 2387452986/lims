package com.lanchuan.lims.report;

import com.lanchuan.lims.common.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningAssetImageValidatorTest {
    @Test
    void parsesRealImageAndRejectsMismatchedOrOversizedContent() throws Exception {
        byte[] signature = image(250, 130, "png");
        assertThat(SigningAssetImageValidator.validate(signature, "image/png"))
                .isEqualTo(new SigningAssetImageValidator.Dimensions(250, 130));
        assertThatThrownBy(() -> SigningAssetImageValidator.validate(signature, "image/jpeg"))
                .isInstanceOf(ApiException.class).hasMessageContaining("格式不一致");

        byte[] oversized = image(4097, 1, "png");
        assertThatThrownBy(() -> SigningAssetImageValidator.validate(oversized, "image/png"))
                .isInstanceOf(ApiException.class).hasMessageContaining("4096");
        assertThatThrownBy(() -> SigningAssetImageValidator.validate(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(ApiException.class).hasMessageContaining("无效");
    }

    private byte[] image(int width, int height, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), format, output);
        return output.toByteArray();
    }
}
