package org.lara.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.theme.*

data class ClassChrome(val name: String, val section: String, val edit: (() -> Unit)? = null, val info: (() -> Unit)? = null, val back: () -> Unit)
val LocalClassChrome = staticCompositionLocalOf<ClassChrome?> { null }

@Composable
fun ClassroomTopBar(title: String, section: String? = null, back: (() -> Unit)? = null, menu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(AppBarHeight).padding(horizontal = LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            if (menu != null) IconButton(onClick = menu, modifier = Modifier.size(MinTouchTarget)) {
                Icon(menuIcon, stringResource(R.string.home_open_navigation), tint = LaraColors.Surface)
            }
            if (back != null) IconButton(onClick = back, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back),
                    Modifier.size(24.dp), tint = LaraColors.Surface)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!section.isNullOrBlank()) Text(section, style = MaterialTheme.typography.bodyMedium, color = LaraColors.PrimaryLight)
            }
            actions()
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
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().laraShadow(),
        shape = LaraShapes.large, color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(LaraSpacing.S4), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!section.isNullOrBlank()) Text(section, style = MaterialTheme.typography.bodyLarge)
            }
            if (!teacher.isNullOrBlank()) Text(teacher, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = LaraSpacing.S4))
        }
    }
}

/** Loading state: one centred spinner, like Google Classroom. [inline] is for a spot inside a list or scrolling column; otherwise it fills the screen. */
@Composable
fun LoadingSpinner(inline: Boolean = false) {
    val label = stringResource(R.string.reading_loading)
    Box((if (inline) Modifier.fillMaxWidth().padding(LaraSpacing.S6) else Modifier.fillMaxSize())
        .semantics { contentDescription = label; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = LaraColors.PrimaryDark)
    }
}
