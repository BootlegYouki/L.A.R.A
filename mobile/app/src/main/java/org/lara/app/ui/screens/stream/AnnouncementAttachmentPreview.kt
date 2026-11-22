package org.lara.app.ui.screens.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.lara.app.R
import org.lara.app.domain.model.Material
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.MinTouchTarget
import java.io.File

/** An offline-only thumbnail: previews never fetch attachment bytes or decode a full-sized image. */
@Composable
internal fun AnnouncementAttachmentPreview(material: Material, open: () -> Unit) {
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
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            bitmap = null
        } finally {
            bitmap = null
            decoded?.recycle()
        }
    }
    return androidx.compose.runtime.rememberUpdatedState(bitmap)
}
