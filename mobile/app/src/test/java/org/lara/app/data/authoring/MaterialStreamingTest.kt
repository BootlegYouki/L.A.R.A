package org.lara.app.data.authoring

import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.lara.app.data.remote.MATERIAL_LIMIT
import org.lara.app.data.repository.copyMaterial

class MaterialStreamingTest {
    @Test fun boundedCopyAcceptsLimitAndRejectsNextByteWithoutWritingIt() {
        var remaining = MATERIAL_LIMIT + 1
        var largestRead = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Must use bounded reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRead = maxOf(largestRead, len)
                if (remaining == 0L) return -1
                val count = minOf(remaining, len.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        var written = 0L
        val output = object : OutputStream() {
            override fun write(b: Int) = error("Must use buffered writes")
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        try { copyMaterial(input, output); fail("Oversize file accepted") }
        catch (error: IllegalArgumentException) { assertEquals("FILE_TOO_LARGE", error.message) }
        assertEquals(MATERIAL_LIMIT, written)
        assertTrue(largestRead <= 32 * 1024)
    }
}
