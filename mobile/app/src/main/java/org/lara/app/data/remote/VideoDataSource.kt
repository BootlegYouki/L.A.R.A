package org.lara.app.data.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.lara.app.domain.VideoFailure
import org.lara.app.domain.VideoProblem

/** Media3 sees a continuous resource while each HTTP read is bounded to 256 KiB. */
@UnstableApi
internal class VideoDataSource(private val upstream: OkHttpDataSource, private val size: Long) : DataSource {
    private var original: DataSpec? = null
    private var position = 0L
    private var remaining = 0L
    private var chunkRemaining = 0L
    private var opened = false
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun getUri(): Uri? = original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec
        position = dataSpec.position
        if (position > size) throw VideoFailure(VideoProblem.INVALID_FILE)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else minOf(dataSpec.length, size - position)
        if (remaining > 0) openChunk()
        return remaining
    }
    private fun openChunk() {
        if (opened) upstream.close()
        chunkRemaining = minOf(256L * 1024, remaining)
        opened = true
        val request = requireNotNull(original).buildUpon().setPosition(position).setLength(chunkRemaining).build()
        upstream.open(request)
        val range = ByteRange.parse(upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull())
        val length = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.firstOrNull()?.toLongOrNull()
        if (upstream.responseCode != 206 || range == null || range.start != position || range.total != size ||
            range.end != position + chunkRemaining - 1 || (length != null && length != chunkRemaining))
            throw VideoFailure(VideoProblem.INVALID_FILE)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) openChunk()
        val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) throw java.io.EOFException("Incomplete media range")
        position += read; remaining -= read; chunkRemaining -= read
        return read
    }
    override fun close() {
        try { if (opened) upstream.close() }
        finally { opened = false; original = null; remaining = 0; chunkRemaining = 0 }
    }
}
