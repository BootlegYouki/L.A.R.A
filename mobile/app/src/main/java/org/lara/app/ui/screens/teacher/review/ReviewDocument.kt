package org.lara.app.ui.screens.teacher.review

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.data.remote.HubFailure
import org.lara.app.ui.screens.classwork.DocBlock
import org.lara.app.ui.screens.classwork.docxBlocks
import org.lara.app.ui.screens.classwork.renderHandout
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.MinTouchTarget

internal enum class ReviewDocumentType { PDF, DOCX }

internal fun reviewDocumentType(file: File): ReviewDocumentType? {
    val header = ByteArray(5)
    val count = file.inputStream().use { it.read(header) }
    if (count == 5 && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) return ReviewDocumentType.PDF
    if (count < 4 || header[0] != 0x50.toByte() || header[1] != 0x4b.toByte()) return null
    return try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(1025).toList()
            // The handout reader expands DOCX entries into memory; reject oversized archives before parsing.
            val safe = entries.size <= 1024 && entries.all { it.size in 0..(8L * 1024 * 1024) } &&
                entries.sumOf { it.size } <= 16L * 1024 * 1024 &&
                entries.count { it.name.startsWith("word/media/") && !it.isDirectory } <= 4
            if (safe && zip.getEntry("word/document.xml") != null) ReviewDocumentType.DOCX else null
        }
    } catch (_: java.io.IOException) { null }
}

@Composable
internal fun ReviewDocument(container: AppContainer, userId: String, submissionId: String, online: Boolean, forbidden: () -> Unit) {
    val context = LocalContext.current
    var file by remember(submissionId) { mutableStateOf<File?>(null) }
    var type by remember(submissionId) { mutableStateOf<ReviewDocumentType?>(null) }
    var failed by remember(submissionId) { mutableStateOf(false) }
    var loading by remember(submissionId) { mutableStateOf(false) }
    var retry by remember(submissionId) { mutableIntStateOf(0) }
    var page by remember(submissionId) { mutableIntStateOf(0) }
    LaunchedEffect(submissionId, online, retry) {
        file = null; type = null; failed = false; page = 0
        if (online) {
            loading = true
            var temp: File? = null
            try {
                temp = File.createTempFile("review-document-", ".tmp", context.cacheDir)
                container.gradingApi.file(userId, submissionId, temp, 250L * 1024 * 1024)
                val detected = withContext(Dispatchers.IO) { reviewDocumentType(temp) }
                if (detected == null) failed = true else { type = detected; file = temp }
                loading = false
                if (detected != null) awaitCancellation()
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) { if (e.status == 403) forbidden() else failed = true }
            catch (_: Exception) { failed = true }
            finally { file = null; temp?.delete(); loading = false }
        }
    }
    Text(stringResource(R.string.review_document), style = MaterialTheme.typography.titleMedium)
    when {
        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        failed -> Column {
            Text(stringResource(R.string.review_document_unavailable))
            TextButton(onClick = { retry++ }, enabled = online, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_retry)) }
        }
        !online -> Text(stringResource(R.string.review_document_offline))
        file != null && type == ReviewDocumentType.PDF -> ReviewPdfPage(requireNotNull(file), page) { page = it }
        file != null && type == ReviewDocumentType.DOCX -> ReviewWordDocument(requireNotNull(file))
    }
}

@Composable
private fun ReviewPdfPage(file: File, page: Int, selectPage: (Int) -> Unit) {
    var raster by remember(file, page) { mutableStateOf<org.lara.app.ui.screens.classwork.HandoutRaster?>(null) }
    var failed by remember(file, page) { mutableStateOf(false) }
    LaunchedEffect(file, page) {
        var decoded: org.lara.app.ui.screens.classwork.HandoutRaster? = null
        try {
            withContext(Dispatchers.IO) { decoded = renderHandout(file, true, page) }
            raster = decoded; decoded = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { decoded?.bitmap?.recycle() }
    }
    val current = raster
    DisposableEffect(current) { onDispose { current?.bitmap?.recycle() } }
    when {
        failed -> Text(stringResource(R.string.review_document_unavailable))
        current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> {
            Image(current.bitmap.asImageBitmap(), stringResource(R.string.review_document), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            Text(stringResource(R.string.reading_page, page + 1, current.pages))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                OutlinedButton(onClick = { selectPage(page - 1) }, enabled = page > 0, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_document_previous)) }
                OutlinedButton(onClick = { selectPage(page + 1) }, enabled = page + 1 < current.pages, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_document_next)) }
            }
        }
    }
}

@Composable
private fun ReviewWordDocument(file: File) {
    var blocks by remember(file) { mutableStateOf<List<DocBlock>?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        try { blocks = withContext(Dispatchers.IO) { docxBlocks(file) }.also { if (it.isEmpty()) failed = true } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val content = blocks
    DisposableEffect(content) { onDispose { content?.filterIsInstance<DocBlock.Pic>()?.forEach { it.bitmap.recycle() } } }
    when {
        failed -> Text(stringResource(R.string.review_document_unavailable))
        content == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> content.forEach { block ->
            when (block) {
                is DocBlock.Para -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                is DocBlock.Pic -> Image(block.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
