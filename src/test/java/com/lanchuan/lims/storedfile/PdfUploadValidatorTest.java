package com.lanchuan.lims.storedfile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfUploadValidatorTest {
    @TempDir Path directory;

    @Test
    void acceptsStaticPdfAndRejectsMalformedOrActiveContent() throws Exception {
        Path staticPdf = directory.resolve("static.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(staticPdf.toFile());
        }
        assertThat(PdfUploadValidator.isSafe(staticPdf)).isTrue();

        Path activePdf = directory.resolve("active.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('unsafe')"));
            document.save(activePdf.toFile());
        }
        assertThat(PdfUploadValidator.isSafe(activePdf)).isFalse();

        Path malformed = directory.resolve("malformed.pdf");
        Files.writeString(malformed, "%PDF-1.4\nnot-a-document\n%%EOF\n");
        assertThat(PdfUploadValidator.isSafe(malformed)).isFalse();
    }
}
