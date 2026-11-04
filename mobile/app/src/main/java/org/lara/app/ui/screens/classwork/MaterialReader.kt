package org.lara.app.ui.screens.classwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.model.Material
import org.lara.app.ui.screens.stream.ReadingSkeleton
import org.lara.app.ui.theme.*
import java.io.File
import kotlin.math.sqrt

data class HandoutRaster(val bitmap: Bitmap, val pages: Int)

/** Decode just the requested page, capped at two million pixels (about 8 MB). */
internal fun renderHandout(file: File, pdf: Boolean, page: Int): HandoutRaster {
    if (pdf) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { current ->
                    val scale = minOf(2f, 2048f / current.width, 2048f / current.height,
                        sqrt(2_000_000f / (current.width.toFloat() * current.height)))
                    val bitmap = Bitmap.createBitmap((current.width * scale).toInt().coerceAtLeast(1),
                        (current.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        current.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return HandoutRaster(bitmap, renderer.pageCount)
                    } catch (e: Exception) { bitmap.recycle(); throw e }
                }
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048 ||
        bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000L) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
    return HandoutRaster(bitmap, 1)
}

@Composable
fun MaterialReader(container: AppContainer, material: Material, close: () -> Unit) {
    BackHandler(onBack = close)
    var page by rememberSaveable(material.id) { mutableIntStateOf(0) }
    var retry by remember(material.id) { mutableIntStateOf(0) }
    var loading by remember(material.id) { mutableStateOf(true) }
    var failed by remember(material.id) { mutableStateOf(false) }
    var file by remember(material.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(material.id, retry) {
        loading = true; failed = false
        try { file = container.materialFiles.open(material.id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val scope = rememberCoroutineScope()
    val flow = remember(material.id) { container.classwork.chunks(material.id, scope) }
    val cached by flow.collectAsStateWithLifecycle()
    val chunks = (cached as? Cached.Content)?.value.orEmpty()
    var raster by remember(material.id, page) { mutableStateOf<HandoutRaster?>(null) }
    var renderFailed by remember(material.id, page) { mutableStateOf(false) }
    val visual = material.mimeType == "application/pdf" || material.mimeType?.startsWith("image/") == true
    LaunchedEffect(file, page, visual, retry) {
        raster = null; renderFailed = false
        if (visual && file != null) {
            var decoded: HandoutRaster? = null
            try {
                withContext(Dispatchers.IO) { decoded = renderHandout(requireNotNull(file), material.mimeType == "application/pdf", page) }
                raster = decoded; decoded = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { renderFailed = true }
            finally { decoded?.bitmap?.recycle() }
        }
    }
    val bitmap = raster?.bitmap
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    Column(Modifier.fillMaxSize().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Text(material.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = close, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.reading_close)) }
        }
        if (loading) ReadingSkeleton()
        if (failed || renderFailed) {
            Text(stringResource(if (renderFailed) R.string.reading_file_unreadable else R.string.reading_file_unavailable))
            TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.reading_retry)) }
        }
        if (visual && raster == null && !loading && !failed && !renderFailed) ReadingSkeleton()
        raster?.let { image ->
            if (material.mimeType == "application/pdf") {
                Text(stringResource(R.string.reading_page, page + 1, image.pages), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.reading_previous))
                    }
                    OutlinedButton(onClick = { page++ }, enabled = page + 1 < image.pages, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.reading_next))
                    }
                }
            }
            ZoomableHandout(image.bitmap, material.title, Modifier.weight(1f).fillMaxWidth())
        }
        if (raster == null && !loading) {
            if (cached == Cached.Loading) ReadingSkeleton()
            else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Text(stringResource(R.string.reading_lesson_text), style = MaterialTheme.typography.titleMedium)
                chunks.forEach { chunk ->
                    chunk.heading?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(chunk.text, style = MaterialTheme.typography.bodyLarge)
                }
                if (chunks.isEmpty()) Text(stringResource(R.string.reading_no_text))
            }
        } else if (chunks.isEmpty() && !loading) Text(stringResource(R.string.reading_no_text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ZoomableHandout(bitmap: Bitmap, title: String, modifier: Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    fun bounded(value: Offset): Offset {
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val x = ((bitmap.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((bitmap.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = bounded(offset + pan)
    }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = bounded(offset) },
                modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.reading_zoom_out)) }
            TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(4f) }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.reading_zoom_in))
            }
        }
        Text(stringResource(R.string.reading_zoom_state, (scale * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport = it }.clipToBounds().transformable(state)) {
            Image(bitmap.asImageBitmap(), title, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            })
        }
    }
}
