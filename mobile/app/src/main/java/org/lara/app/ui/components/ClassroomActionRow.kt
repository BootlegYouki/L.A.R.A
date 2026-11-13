package org.lara.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import org.lara.app.ui.theme.*

@Composable
fun ClassroomActionRow(
    title: String,
    supportingText: String? = null,
    @DrawableRes icon: Int,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) LaraColors.DangerDark else LaraColors.PrimaryDark
    Surface(onClick = onClick, enabled = enabled, color = LaraColors.Surface,
        contentColor = color, shape = LaraShapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = PrimaryTouchTarget).padding(LaraSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(LaraSpacing.S6),
                tint = if (enabled) color else LaraColors.TextMuted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) color else LaraColors.TextMuted)
                if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodyLarge,
                    color = LaraColors.TextSecondary)
            }
        }
    }
}
