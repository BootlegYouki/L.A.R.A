package org.lara.app.ui.screens.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.VideoSource
import org.lara.app.domain.model.Material
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.MinTouchTarget
import java.io.File

/** Inline media preview for class posts and assignment instructions. Bytes are cached to disk; images decode to a small sample. */
@Composable
internal fun AnnouncementAttachmentPreview(container: AppContainer, userId: String, material: Material, reachable: Boolean, open: () -> Unit) {
    val context = LocalContext.current
    var probedImage by remember(material.id, material.updatedAt) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(material.id, material.updatedAt, material.mimeType, material.fileType, reachable) {
        if (reachable && material.mimeType.isNullOrBlank() && material.fileType != "VIDEO") {
            probedImage = try { container.materialFiles.probeImageSignature(material.id) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
        }
    }
    val image = material.mimeType?.startsWith("image/") == true || probedImage == true
    val video = material.mimeType?.startsWith("video/") == true || material.fileType == "VIDEO"
    val savedThumbnail by rememberLocalMaterialThumbnail(material.localFilePath.takeIf { image }, context.filesDir)
    var bitmap by remember(material.id, material.updatedAt) { mutableStateOf<Bitmap?>(null) }
    var imageFailed by remember(material.id, material.updatedAt) { mutableStateOf(false) }
    LaunchedEffect(material.id, material.updatedAt, image, material.localFilePath, reachable) {
        bitmap = null
        imageFailed = false
        if (image && (reachable || material.localFilePath != null)) {
            var decoded: Bitmap? = null
            try {
                val file = container.materialFiles.open(material.id, save = false, maxBytes = 8L * 1024 * 1024)
                decoded = withContext(Dispatchers.IO) {
                    if (material.mimeType.isNullOrBlank() && !file.hasImageSignature()) return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        var sample = 1
                        while (bounds.outWidth / sample > 960 || bounds.outHeight / sample > 720 ||
                            bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 260_000L) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                bitmap = decoded
                if (decoded == null) imageFailed = true else kotlinx.coroutines.awaitCancellation()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { bitmap = null; imageFailed = true }
            finally { decoded?.recycle(); bitmap = null }
        }
    }
    if ((!image && !video) || image && (imageFailed || !reachable && material.localFilePath == null)) {
        ExistingAttachmentCard(material, open)
        return
    }
    Surface(color = LaraColors.Surface, shape = LaraShapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, LaraColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(LaraSpacing.S2), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            when {
                image -> {
                    val preview = bitmap
                    val localPreview = savedThumbnail
                    if (preview != null) Image(preview.asImageBitmap(), stringResource(R.string.preview_image_attachment),
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(LaraShapes.small).clickable(onClick = open),
                        contentScale = ContentScale.Fit)
                    else if (localPreview != null) Image(localPreview.asImageBitmap(), stringResource(R.string.preview_image_attachment),
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(LaraShapes.small).clickable(onClick = open), contentScale = ContentScale.Fit)
                    else Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = LaraColors.PrimaryDark, strokeWidth = 2.dp)
                    }
                }
                video -> InlineVideoPreview(container, userId, material)
            }
            TextButton(onClick = open, modifier = Modifier.heightIn(min = org.lara.app.ui.theme.MinTouchTarget)) {
                Text(material.title, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ExistingAttachmentCard(material: Material, open: () -> Unit) {
    val context = LocalContext.current
    val image = material.mimeType?.startsWith("image/") == true
    val thumbnail by rememberLocalMaterialThumbnail(material.localFilePath.takeIf { image }, context.filesDir)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open),
        color = LaraColors.Surface,
        shape = LaraShapes.medium,
        border = BorderStroke(1.dp, LaraColors.Border),
    ) {
        Row(
            Modifier.heightIn(min = MinTouchTarget).padding(LaraSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
        ) {
            if (image) {
                val preview = thumbnail
                if (preview != null) {
                    Image(preview.asImageBitmap(), stringResource(R.string.preview_image_attachment),
                        Modifier.size(88.dp, 64.dp).clip(LaraShapes.small), contentScale = ContentScale.Crop)
                } else {
                    Surface(Modifier.size(88.dp, 64.dp), color = LaraColors.SurfaceSubtle, shape = LaraShapes.small) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_image), stringResource(R.string.preview_image_unavailable),
                                Modifier.size(28.dp), tint = LaraColors.TextSecondary)
                        }
                    }
                }
            } else {
                Surface(Modifier.size(48.dp), color = LaraColors.InfoLight, shape = LaraShapes.medium) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (material.fileType == "VIDEO") R.drawable.ic_play else R.drawable.ic_books_regular),
                            null, Modifier.size(24.dp), tint = LaraColors.InfoDark)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                Text(material.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                if (material.localFilePath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                        Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(16.dp), tint = LaraColors.PrimaryDark)
                        Text(stringResource(R.string.reading_saved_device_badge), style = MaterialTheme.typography.bodySmall,
                            color = LaraColors.TextSecondary)
                    }
                } else {
                    Text(stringResource(R.string.preview_open_attachment), style = MaterialTheme.typography.bodySmall,
                        color = LaraColors.TextSecondary)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun InlineVideoPreview(container: AppContainer, userId: String, material: Material) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val id = material.id
    var started by remember(id, userId) { mutableStateOf(false) }
    var source by remember(id, userId) { mutableStateOf<VideoSource?>(null) }
    var failed by remember(id, userId) { mutableStateOf(false) }
    var player by remember(id, userId) { mutableStateOf<ExoPlayer?>(null) }
    var autoPlay by remember(id, userId) { mutableStateOf(true) }
    var positionMs by remember(id, userId) { mutableLongStateOf(0L) }
    LaunchedEffect(id, userId, started) {
        if (!started) return@LaunchedEffect
        source = try { container.videos.source(id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true; null }
    }
    if (failed) {
        Text(stringResource(R.string.video_invalid), Modifier.padding(LaraSpacing.S3), color = LaraColors.TextSecondary)
        return
    }
    if (!started) {
        Surface(color = LaraColors.SurfaceSubtle, shape = LaraShapes.small) {
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(onClick = { started = true }, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_play), stringResource(R.string.video_play), tint = LaraColors.PrimaryDark)
                }
            }
        }
        return
    }
    DisposableEffect(source, lifecycle) {
        var active: ExoPlayer? = null
        fun start() {
            if (active == null && source != null) active = runCatching {
                container.createVideoPlayer(requireNotNull(source)).apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failed = true }
                    })
                    seekTo(positionMs)
                    playWhenReady = autoPlay
                    prepare()
                }
            }.getOrElse { failed = true; null }.also { player = it; autoPlay = false }
        }
        fun stop() {
            active?.let {
                positionMs = it.currentPosition.coerceAtLeast(0L)
                it.release()
            }
            active = null
            player = null
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 260.dp).clip(LaraShapes.small), contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply { useController = true; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (source == null) CircularProgressIndicator(Modifier.size(28.dp), color = LaraColors.PrimaryDark, strokeWidth = 2.dp)
    }
}

private fun File.hasImageSignature(): Boolean = runCatching {
    inputStream().use { input ->
        val head = ByteArray(12)
        var count = 0
        while (count < head.size) {
            val read = input.read(head, count, head.size - count)
            if (read < 0) break
            count += read
        }
        (count >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4e.toByte() && head[3] == 0x47.toByte()) ||
            (count >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) ||
            (count >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WEBP") ||
            (count >= 6 && (String(head, 0, 6) == "GIF87a" || String(head, 0, 6) == "GIF89a"))
    }
}.getOrDefault(false)

@Composable
private fun rememberLocalMaterialThumbnail(path: String?, filesDir: File): androidx.compose.runtime.State<Bitmap?> {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        var decoded: Bitmap? = null
        try {
            decoded = withContext(Dispatchers.IO) {
                if (path.isNullOrBlank()) return@withContext null
                val root = File(filesDir, "handouts").canonicalFile
                val file = File(path).canonicalFile
                if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 480 ||
                    bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 180_000L) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            bitmap = decoded
            kotlinx.coroutines.awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            bitmap = null
        } finally {
            bitmap = null
            decoded?.recycle()
        }
    }
    return rememberUpdatedState(bitmap)
}
