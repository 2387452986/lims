package com.lanchuan.lims.report;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportJobWorkerTest {
    @Test
    void appliesFirstLastAndEveryPageRulesToPdf() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            for (PDPage page : document.getPages()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.addRect(36, 36, 100, 40);
                    content.stroke();
                }
            }
            document.save(output);
            source = output.toByteArray();
        }

        byte[] image = image();
        SigningAssetService.AssetContent content = new SigningAssetService.AssetContent("image/png", image);
        List<ReportJobWorker.RenderedPosition> positions = List.of(
                rendered("SIGNATURE", "FIRST", "20", "30" , "40", "20", content),
                rendered("SEAL", "EVERY", "120", "180", "30", "30", content),
                rendered("SIGNATURE", "LAST", "60", "210", "48", "20", content));

        byte[] result = ReportJobWorker.applyScheme(source, positions);

        assertTrue(result.length > source.length);
        try (PDDocument document = Loader.loadPDF(result)) {
            assertEquals(2, document.getNumberOfPages());
            assertEquals(2, countImages(document.getPage(0)));
            assertEquals(2, countImages(document.getPage(1)));
        }
    }

    private ReportJobWorker.RenderedPosition rendered(String type, String rule, String x, String y,
                                                       String width, String height,
                                                       SigningAssetService.AssetContent content) {
        return new ReportJobWorker.RenderedPosition(new SignatureSchemeService.RenderPosition(type, UUID.randomUUID(),
                type, rule, new BigDecimal(x), new BigDecimal(y), new BigDecimal(width), new BigDecimal(height)), content);
    }

    private int countImages(PDPage page) throws Exception {
        int count = 0;
        for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().isImageXObject(name)) count++;
        }
        return count;
    }

    private byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(60, 30, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(200, 20, 20, 220));
        graphics.fillOval(2, 2, 26, 26);
        graphics.setColor(new Color(20, 40, 160, 220));
        graphics.fillRect(30, 8, 28, 14);
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
