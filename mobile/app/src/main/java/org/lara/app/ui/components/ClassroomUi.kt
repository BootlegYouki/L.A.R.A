package org.lara.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.screens.settings.SettingsAction
import org.lara.app.ui.theme.*

data class ClassChrome(val name: String, val section: String, val back: () -> Unit)
val LocalClassChrome = staticCompositionLocalOf<ClassChrome?> { null }

@Composable
fun ClassroomTopBar(title: String, section: String? = null, back: (() -> Unit)? = null) {
    Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            if (back != null) IconButton(onClick = back, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back),
                    Modifier.size(24.dp), tint = LaraColors.Surface)
            }
            Column(Modifier.weight(1f).padding(vertical = LaraSpacing.S3)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!section.isNullOrBlank()) Text(section, style = MaterialTheme.typography.bodyMedium, color = LaraColors.PrimaryLight)
            }
            SettingsAction()
        }
    }
}

@Composable
fun ClassroomSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().laraShadow(), shape = LaraShapes.large, color = LaraColors.Surface,
        border = BorderStroke(1.dp, LaraColors.Border)) {
        Column(Modifier.padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3), content = content)
    }
}

@Composable
fun ClassroomCard(name: String, section: String?, teacher: String?, status: String, ready: Boolean, open: () -> Unit) {
    Surface(onClick = open, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).laraShadow(),
        shape = LaraShapes.large, color = LaraColors.Surface, border = BorderStroke(1.dp, LaraColors.Border)) {
        Column(Modifier.padding(LaraSpacing.S5), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface, shape = LaraShapes.medium) {
                Box(Modifier.size(PrimaryTouchTarget), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(32.dp))
                }
            }
            Text(name, style = MaterialTheme.typography.headlineMedium)
            if (!section.isNullOrBlank()) Text(section, color = LaraColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
            Surface(color = if (ready) LaraColors.PrimaryLight else LaraColors.WarningLight, shape = CircleShape) {
                Row(Modifier.padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S2),
                    horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(if (ready) R.drawable.ic_check_circle else R.drawable.ic_info), null,
                        Modifier.size(20.dp), tint = if (ready) LaraColors.PrimaryDark else LaraColors.WarningDark)
                    Text(status, style = MaterialTheme.typography.labelLarge,
                        color = if (ready) LaraColors.PrimaryDark else LaraColors.WarningBannerText)
                }
            }
            if (!teacher.isNullOrBlank()) Text(teacher, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
            if (ready) {
                HorizontalDivider(color = LaraColors.Border)
                Text(stringResource(R.string.enrollment_open), color = LaraColors.PrimaryDark, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
