package org.lara.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
internal fun AttachmentChip(
    fileType: String,
    name: String? = null,
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
) {
    val label = stringResource(if (fileType == "IMAGE") R.string.homework_photo_item else R.string.homework_document_item)
    val icon = when (fileType) {
        "IMAGE" -> R.drawable.ic_image
        "VIDEO" -> R.drawable.ic_play
        else -> R.drawable.ic_books_regular
    }
    Surface(modifier.fillMaxWidth(), shape = LaraShapes.medium, color = LaraColors.Surface,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Row(
            Modifier.heightIn(min = MinTouchTarget).padding(LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
        ) {
            if (preview == null) Icon(painterResource(icon), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
            else preview()
            Text(name ?: label, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
