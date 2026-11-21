package org.lara.app.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.repository.HomeworkAttachment

class HomeworkDocumentContentTest {
    @Test fun pdfRequiresPdfSignature() {
        val file = File.createTempFile("synthetic", ".pdf")
        try {
            file.writeText("%PDF-1.4\n")
            assertTrue(HomeworkAttachment.hasExpectedDocumentContent(file))
            file.writeText("not a PDF")
            assertFalse(HomeworkAttachment.hasExpectedDocumentContent(file))
        } finally { file.delete() }
    }

    @Test fun docxRequiresWordArchiveEntries() {
        val file = File.createTempFile("synthetic", ".docx")
        try {
            file.writeText("not a zip")
            assertFalse(HomeworkAttachment.hasExpectedDocumentContent(file))
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("[Content_Types].xml", "word/document.xml").forEach { name ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(1); zip.closeEntry()
                }
            }
            assertTrue(HomeworkAttachment.hasExpectedDocumentContent(file))
        } finally { file.delete() }
    }
}
