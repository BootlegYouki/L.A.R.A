package org.lara.app.ui.screens.teacher.review

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.ui.screens.classwork.DocBlock
import org.lara.app.ui.screens.classwork.docxBlocks

class ReviewDocumentTest {
    @Test fun identifiesPdfFromBytesWithoutRelyingOnAFileName() {
        val file = File.createTempFile("review", ".tmp")
        try {
            file.writeText("%PDF-1.4\n")
            assertEquals(ReviewDocumentType.PDF, reviewDocumentType(file))
            file.writeText("not a document")
            assertNull(reviewDocumentType(file))
        } finally { file.delete() }
    }

    @Test fun identifiesAndReadsWordDocumentAndRejectsUnrelatedArchives() {
        val file = File.createTempFile("review", ".tmp")
        fun archive(entry: String, content: String) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        try {
            archive("word/document.xml", "<w:document><w:p><w:r><w:t>Student work</w:t></w:r></w:p></w:document>")
            assertEquals(ReviewDocumentType.DOCX, reviewDocumentType(file))
            assertEquals("Student work", (docxBlocks(file).single() as DocBlock.Para).text)
            archive("other.txt", "not a Word file")
            assertNull(reviewDocumentType(file))
        } finally { file.delete() }
    }

    @Test fun rejectsWordArchivesWithTooManyImages() {
        val file = File.createTempFile("review", ".tmp")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                (listOf("word/document.xml") + (1..5).map { "word/media/$it.png" }).forEach { name ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(1); zip.closeEntry()
                }
            }
            assertNull(reviewDocumentType(file))
        } finally { file.delete() }
    }
}
