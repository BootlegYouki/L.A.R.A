package org.lara.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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

/** Green back bar used by full-screen pages (assignment, announcement, material), like Classroom's detail screens. */
@Composable
fun ClassroomBackBar(onBack: () -> Unit, title: String? = null, onAi: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(LaraColors.PrimaryDark).statusBarsPadding().height(AppBarHeight).padding(horizontal = LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back), Modifier.size(LaraSpacing.S6), tint = androidx.compose.ui.graphics.Color.White)
        }
        title?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        if (onAi != null) {
            if (title == null) Spacer(Modifier.weight(1f))
            IconButton(onClick = onAi, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_star_four), stringResource(R.string.ai_tutor_open), Modifier.size(LaraSpacing.S6), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
