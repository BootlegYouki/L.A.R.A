package org.lara.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.lara.app.R
import org.lara.app.ui.theme.*

/** Detail content already sits beneath the class app bar; do not add another system-bar inset. */
@Composable
fun ClassroomDetailHeader(title: String, @StringRes backLabel: Int, onBack: () -> Unit) {
    Surface(color = LaraColors.Surface, contentColor = LaraColors.TextPrimary) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S2),
                horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_arrow_left), stringResource(backLabel),
                        Modifier.size(LaraSpacing.S6), tint = LaraColors.PrimaryDark)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(color = LaraColors.Border)
        }
    }
}
