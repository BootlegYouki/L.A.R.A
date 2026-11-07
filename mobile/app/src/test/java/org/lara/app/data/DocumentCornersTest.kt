package org.lara.app.data

import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.repository.DocumentScanner

class DocumentCornersTest {
    @Test fun acceptsFullPageAndPerspectiveCorners() {
        assertTrue(DocumentScanner.valid(DocumentScanner.fullPage()))
        assertTrue(DocumentScanner.valid(floatArrayOf(.1f, .1f, .9f, .2f, .8f, .9f, .05f, .8f)))
    }

    @Test fun rejectsCrossedCollapsedAndOutOfBoundsCorners() {
        assertFalse(DocumentScanner.valid(floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)))
        assertFalse(DocumentScanner.valid(floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(DocumentScanner.valid(floatArrayOf(-.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(DocumentScanner.valid(floatArrayOf(0f, Float.NaN, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(DocumentScanner.valid(floatArrayOf(0f, 0f, .01f, 0f, .01f, .01f, 0f, .01f)))
    }
}
