package org.lara.app.ui.screens.classwork

import org.junit.Assert.assertEquals
import org.junit.Test

class DocxReaderTest {
    @Test fun acceptsXmlAtTheLimit() {
        assertEquals("<w:p/>", readDocxXml("<w:p/>".byteInputStream(), 6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExpandedXmlOverTheLimit() {
        readDocxXml("<w:p/>".byteInputStream(), 5)
    }
}
