package org.lara.app.ui.screens.classwork

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing

/** Classroom-style attachment preview, just under half the width so two sit side by side: a rounded 16:10 card the learner taps to open the file. Without an image it shows the file's icon (or its first words). */
@Composable
internal fun AttachmentThumb(bitmap: Bitmap?, icon: Int, play: Boolean = false, text: String? = null, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(0.48f).aspectRatio(1.6f).clip(LaraShapes.medium).border(1.dp, LaraColors.Border, LaraShapes.medium)
        .background(if (play) Color.Black else LaraColors.SurfaceSubtle).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1.6f),
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else if (text != null) Text(text, Modifier.padding(LaraSpacing.S3), style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        else Icon(painterResource(icon), null, Modifier.size(40.dp), tint = LaraColors.TextSecondary)
        if (play) Icon(painterResource(org.lara.app.R.drawable.ic_play), null, Modifier.size(48.dp), tint = Color.White)
    }
}
