package com.lanchuan.lims.storedfile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Structural PDF validation for user supplied evidence files. */
final class PdfUploadValidator {
    private static final int MAX_PAGES = 5_000;
    private static final int MAX_OBJECTS = 200_000;
    private static final Set<String> ACTIVE_NAMES = Set.of(
            "JavaScript", "JS", "Launch", "OpenAction", "AA", "URI", "GoToR",
            "SubmitForm", "ImportData", "RichMedia", "RichMediaExecute", "Rendition",
            "Movie", "Sound", "XFA", "EmbeddedFile", "EmbeddedFiles", "Filespec", "EF", "AF");

    private PdfUploadValidator() {}

    static boolean isSafe(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) return false;
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > MAX_PAGES) return false;
            if (document.getDocument().getXrefTable().size() > MAX_OBJECTS) return false;

            ArrayDeque<COSBase> pending = new ArrayDeque<>();
            pending.add(document.getDocument().getTrailer());
            for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
                pending.add(document.getDocument().getObjectFromPool(key));
            }
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!pending.isEmpty()) {
                COSBase value = pending.removeFirst();
                if (value == null || !seen.add(value) || seen.size() > MAX_OBJECTS) continue;
                if (value instanceof COSObject object) {
                    if (!object.isObjectNull()) pending.add(object.getObject());
                } else if (value instanceof COSDictionary dictionary) {
                    for (COSName name : dictionary.keySet()) {
                        if (ACTIVE_NAMES.contains(name.getName())) return false;
                    }
                    for (COSBase child : dictionary.getValues()) if (child != null) pending.add(child);
                } else if (value instanceof COSArray array) {
                    for (COSBase child : array) if (child != null) pending.add(child);
                } else if (value instanceof COSName name && ACTIVE_NAMES.contains(name.getName())) {
                    return false;
                }
            }
            return seen.size() <= MAX_OBJECTS;
        } catch (IOException | RuntimeException invalidPdf) {
            return false;
        }
    }
}
