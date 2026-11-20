package org.lara.app.ui.screens.classwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.Cached
import org.lara.app.domain.model.Material
import org.lara.app.ui.components.ClassroomDetailHeader
import org.lara.app.ui.components.DiscoveryBanner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
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

/** One finished preview, drawn before the page opens; the reader takes it over and recycles the bitmap. */
private class Preloaded(val id: String, val file: File, val raster: HandoutRaster?, val blocks: List<DocBlock>?)
private var preloaded: Preloaded? = null

suspend fun preloadMaterial(container: AppContainer, material: Material) {
    val file = try { container.materialFiles.open(material.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
    val pdf = material.mimeType == "application/pdf"
    val image = material.mimeType?.startsWith("image/") == true
    val docx = material.mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || material.title.endsWith(".docx", true)
    val raster = if (pdf || image) withContext(Dispatchers.IO) { runCatching { renderHandout(file, pdf, 0) }.getOrNull() } else null
    val blocks = if (docx) withContext(Dispatchers.IO) { runCatching { docxBlocks(file) }.getOrNull() } else null
    preloaded?.raster?.bitmap?.recycle()
    preloaded = Preloaded(material.id, file, raster, blocks)
}

@Composable
fun MaterialReader(container: AppContainer, material: Material, close: () -> Unit) {
    BackHandler(onBack = close)
    var page by rememberSaveable(material.id) { mutableIntStateOf(0) }
    var retry by remember(material.id) { mutableIntStateOf(0) }
    var pre by remember(material.id) { mutableStateOf(preloaded?.takeIf { it.id == material.id }.also { preloaded = null }) }
    var loading by remember(material.id) { mutableStateOf(pre == null) }
    var failed by remember(material.id) { mutableStateOf(false) }
    var removed by remember(material.id) { mutableStateOf(false) }
    var file by remember(material.id) { mutableStateOf<File?>(pre?.file) }
    var viewing by rememberSaveable(material.id) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmRemove by rememberSaveable(material.id) { mutableStateOf(false) }
    LaunchedEffect(material.id, retry, removed) {
        if (removed) {
            // Not saved: still fetch a temporary copy so the preview and viewer work, like a streamed video.
            loading = true; failed = false
            try { file = container.materialFiles.open(material.id, save = false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { loading = false }
            return@LaunchedEffect
        }
        if (pre != null) { pre = null; loading = false; return@LaunchedEffect }
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
    var raster by remember(material.id, page) { mutableStateOf<HandoutRaster?>(pre?.raster?.takeIf { page == 0 }) }
    var renderFailed by remember(material.id, page) { mutableStateOf(false) }
    val visual = material.mimeType == "application/pdf" || material.mimeType?.startsWith("image/") == true
    LaunchedEffect(file, page, visual, retry) {
        if (raster != null && retry == 0) return@LaunchedEffect
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
    val docx = material.mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || material.title.endsWith(".docx", true)
    var docBlocks by remember(material.id) { mutableStateOf<List<DocBlock>?>(pre?.blocks) }
    LaunchedEffect(file, docx) {
        val f = file
        if (docx && docBlocks == null && f != null) docBlocks = withContext(Dispatchers.IO) { runCatching { docxBlocks(f) }.getOrNull() }
    }
    val bitmap = raster?.bitmap
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    val first = raster
    var aiSoon by remember { mutableStateOf(false) }
    // The tutor only works from lesson text, so a photo or file without any gets no AI button.
    val onAi: (() -> Unit)? = if (chunks.isEmpty()) null else { { aiSoon = true } }
    if (aiSoon) { TutorChat(container, material) { aiSoon = false }; return }
    BackHandler(enabled = viewing) { viewing = false }
    if (viewing && material.mimeType == "application/pdf" && first != null && file != null) { PageViewer(requireNotNull(file), material, first.pages, onAi) { viewing = false }; return }
    val blocks = docBlocks
    if (viewing && docx && !blocks.isNullOrEmpty()) { DocxViewer(material, blocks, onAi) { viewing = false }; return }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LaraColors.Surface)) {
        org.lara.app.ui.components.ClassroomBackBar(close, onAi = onAi)
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewerHeight = (maxHeight - MinTouchTarget * 2).coerceAtLeast(PrimaryTouchTarget * 3)
            org.lara.app.ui.components.PullRefresh({
                retry++
                // The load starts after this recomposes; wait for it to begin, then finish, so the spinner matches.
                kotlinx.coroutines.withTimeoutOrNull(1_000) { snapshotFlow { loading }.first { it } }
                kotlinx.coroutines.withTimeoutOrNull(15_000) { snapshotFlow { loading }.first { !it } }
            }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Text(material.title, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.material_attachments), style = MaterialTheme.typography.titleMedium)
                // A missing file is a toast on tap; pulling the page down is the retry.
                val unavailable = failed || renderFailed || (docx && !loading && blocks.isNullOrEmpty())
                val open = { if (unavailable) android.widget.Toast.makeText(context, R.string.reading_file_unavailable, android.widget.Toast.LENGTH_SHORT).show() else viewing = true }
                // Reserve the card while the preview renders so the buttons below do not jump.
                Column {
                if (visual || docx) {
                    val docPic = blocks?.filterIsInstance<DocBlock.Pic>()?.firstOrNull()?.bitmap
                    AttachmentThumb(bitmap ?: docPic, R.drawable.ic_books_regular,
                        text = if (bitmap == null && docPic == null) blocks?.filterIsInstance<DocBlock.Para>()?.firstOrNull()?.text else null, onClick = open)
                }
                if (visual && bitmap != null) Row(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(24.dp), tint = LaraColors.DangerDark)
                    Text(material.title, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (docx && file != null) Row(Modifier.fillMaxWidth().clickable(onClick = open).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(24.dp), tint = LaraColors.Primary)
                    Text(material.title, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                }
                if (file != null && !removed) OutlinedButton(onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.video_remove)) }
                if (removed) OutlinedButton(onClick = { removed = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.video_save)) }
                if (visual && !failed && !renderFailed && (loading || raster == null))
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                if (raster == null && !loading && !docx && !(visual && !failed && !renderFailed)) {
                    if (cached == Cached.Loading) org.lara.app.ui.components.LoadingSpinner(inline = true)
                    else if (chunks.isNotEmpty()) {
                        Text(stringResource(R.string.reading_lesson_text), style = MaterialTheme.typography.titleMedium)
                        chunks.forEach { chunk ->
                            chunk.heading?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            Text(chunk.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            }
        }
    }
    if (confirmRemove) org.lara.app.ui.components.NativeConfirmDialog(stringResource(R.string.video_remove), stringResource(R.string.material_remove_confirmation),
        stringResource(R.string.video_remove_confirm), stringResource(R.string.video_remove_cancel),
        { confirmRemove = false; scope.launch { container.materialFiles.remove(material.id); removed = true } }, { confirmRemove = false })
    // Same as the video: the photo floats over the dimmed page and a tap outside closes it.
    if (viewing && bitmap != null && material.mimeType != "application/pdf") ImagePreview(bitmap, material.title) { viewing = false }
    }
}

private sealed interface DocBlock {
    data class Para(val text: String) : DocBlock
    class Pic(val bitmap: Bitmap) : DocBlock
}

/** A .docx is a zip; the body is word/document.xml. shortcut: paragraphs and inline pictures only (no tables, fonts or layout), upgrade if handouts need them. */
private fun docxBlocks(file: File): List<DocBlock> = java.util.zip.ZipFile(file).use { zip ->
    fun read(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it).readBytes() }
    val xml = String(requireNotNull(read("word/document.xml")))
    val targets = read("word/_rels/document.xml.rels")?.let { String(it) }
        ?.let { rels -> Regex("""<Relationship [^>]*?Id="([^"]+)"[^>]*?Target="([^"]+)"""").findAll(rels).associate { it.groupValues[1] to it.groupValues[2] } }.orEmpty()
    Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL).findAll(xml).flatMap { match ->
        val paragraph = match.value
        val text = paragraph.replace("<w:tab/>", "\t").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim()
        val pictures = Regex("""r:embed="([^"]+)"""").findAll(paragraph).mapNotNull { embed ->
            val bytes = targets[embed.groupValues[1]]?.let { read("word/$it") } ?: return@mapNotNull null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { DocBlock.Pic(it) }
        }
        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { DocBlock.Para(it) }).asSequence() + pictures
    }.toList()
}

/** The text and pictures of a Word file on a page of their own, like the PDF viewer. */
@Composable
private fun DocxViewer(material: Material, blocks: List<DocBlock>, onAi: (() -> Unit)?, close: () -> Unit) {
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(LaraColors.Surface)) {
        org.lara.app.ui.components.ClassroomBackBar(close, title = material.title, onAi = onAi)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            blocks.forEach { block ->
                when (block) {
                    is DocBlock.Para -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    is DocBlock.Pic -> Image(block.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth(),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(bitmap: android.graphics.Bitmap, title: String, close: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).transformable(zoom)
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center) {
        // Image keeps a small bitmap at its own size, so the box is sized from the aspect ratio: full width, capped to 80% of the height.
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth - LaraSpacing.S4 * 2, maxHeight * 0.8f * ratio)
        Image(bitmap.asImageBitmap(), title, Modifier.width(width).aspectRatio(ratio)
            // graphicsLayer outside the clip: the zoomed photo grows past its first box instead of being cut by it.
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
            .clip(LaraShapes.medium).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    }
}

/** Classroom-style viewer: every page stacked in one scroll, a page pill, pinch to zoom. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun PageViewer(file: File, material: Material, pages: Int, onAi: (() -> Unit)?, close: () -> Unit) {
    val pdf = material.mimeType == "application/pdf"
    val list = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    // shortcut: zooms the whole list around its centre with no sideways pan, upgrade if learners need to read zoomed text edge to edge.
    val zoom = rememberTransformableState { change, _, _ -> scale = (scale * change).coerceIn(1f, 4f) }
    Column(Modifier.fillMaxSize().background(LaraColors.SurfaceSubtle)) {
        org.lara.app.ui.components.ClassroomBackBar(close, material.title, onAi = onAi)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().clipToBounds().transformable(zoom, canPan = { false })
                .graphicsLayer { scaleX = scale; scaleY = scale },
                state = list, contentPadding = PaddingValues(LaraSpacing.S2), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                items(pages) { index -> PdfPage(file, pdf, index, material.title) }
            }
            Surface(Modifier.align(Alignment.BottomCenter).padding(LaraSpacing.S4), color = LaraColors.PrimaryDark, shape = LaraShapes.large, shadowElevation = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZoomButton("−", R.string.reading_zoom_out, scale > 1f) { scale = (scale - 0.5f).coerceAtLeast(1f) }
                    if (pages > 1) Text(stringResource(R.string.reading_page, list.firstVisibleItemIndex + 1, pages), Modifier.padding(horizontal = LaraSpacing.S2),
                        style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
                    ZoomButton("+", R.string.reading_zoom_in, scale < 4f) { scale = (scale + 0.5f).coerceAtMost(4f) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(file: File, pdf: Boolean, index: Int, title: String) {
    var raster by remember(file, index) { mutableStateOf<HandoutRaster?>(null) }
    LaunchedEffect(file, index) {
        raster = try { withContext(Dispatchers.IO) { renderHandout(file, pdf, index) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val bitmap = raster?.bitmap
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    if (bitmap == null) Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(androidx.compose.ui.graphics.Color.White))
    else Image(bitmap.asImageBitmap(), title, Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
}

@Composable
private fun ZoomButton(symbol: String, description: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(description)
    IconButton(onClick, Modifier.size(MinTouchTarget).semantics { contentDescription = label }, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = androidx.compose.ui.graphics.Color.White,
            disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f))) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}
