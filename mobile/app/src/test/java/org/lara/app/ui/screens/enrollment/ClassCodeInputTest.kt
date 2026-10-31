package org.lara.app.ui.screens.enrollment

import androidx.compose.ui.text.AnnotatedString
import org.junit.Test
import org.junit.Assert.*
import org.lara.app.domain.enrollment.ClassCode

class ClassCodeInputTest {
    @Test fun groupedAndUngroupedEditsDisplayIdenticallyWithoutRewritingTheirInput() {
        for (raw in listOf("K7M4QX", "K7M-4QX", " k7m-4qx ")) {
            val transformed = GroupedClassCode.filter(AnnotatedString(raw))
            assertEquals("K7M-4QX", transformed.text.text)
            assertEquals("K7M4QX", ClassCode.canonicalOrNull(raw))
            assertEquals(7, transformed.offsetMapping.originalToTransformed(raw.length))
            assertEquals(raw.length, transformed.offsetMapping.transformedToOriginal(7))
        }
    }
    @Test fun rawHyphenDoesNotShiftTheFollowingDigit() {
        val raw = GroupedClassCode.filter(AnnotatedString("K7M-4QX"))
        assertEquals(5, raw.offsetMapping.originalToTransformed(5))
        assertEquals(5, raw.offsetMapping.transformedToOriginal(5))
        assertEquals("K7M-4", GroupedClassCode.filter(AnnotatedString("K7M-4")).text.text)
    }
    @Test fun allMappingsStayInBoundsIncludingInvalidAndEmptyDrafts() {
        for (raw in listOf("", "-", " ", "K7M", "K7M-", "K7M4QX", "K7M-4QX", " k7m-4qx ", "TOO-LONG-CODE")) {
            val transformed = GroupedClassCode.filter(AnnotatedString(raw))
            for (i in 0..raw.length) assertTrue(transformed.offsetMapping.originalToTransformed(i) in 0..transformed.text.length)
            for (i in 0..transformed.text.length) assertTrue(transformed.offsetMapping.transformedToOriginal(i) in 0..raw.length)
        }
    }
}
