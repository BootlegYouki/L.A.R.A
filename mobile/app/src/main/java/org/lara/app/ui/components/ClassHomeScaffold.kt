package org.lara.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lara.app.R
import org.lara.app.ui.screens.settings.LocalOpenSettings
import org.lara.app.ui.theme.*

data class HomeClass(val id: String, val name: String, val section: String)

// Phosphor Regular list and plus paths (256 viewport, MIT; see design-system/assets/phosphor).
private fun homeIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 16f, strokeLineCap = StrokeCap.Round, pathBuilder = draw)
    }.build()
private val menuIcon = homeIcon("List") {
    moveTo(40f, 64f); horizontalLineTo(216f)
    moveTo(40f, 128f); horizontalLineTo(216f)
    moveTo(40f, 192f); horizontalLineTo(216f)
}
private val plusIcon = homeIcon("Plus") {
    moveTo(40f, 128f); horizontalLineTo(216f)
    moveTo(128f, 40f); verticalLineTo(216f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassHomeScaffold(
    classes: List<HomeClass>,
    teacher: Boolean,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onOpenClass: (String) -> Unit,
    onManageClasses: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openSettings = LocalOpenSettings.current
    var adding by rememberSaveable { mutableStateOf(false) }
    val action = stringResource(if (teacher) R.string.classes_create else R.string.enrollment_join_title)
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, scrimColor = LaraColors.TextPrimary.copy(alpha = 0.5f), drawerContent = {
        ModalDrawerSheet(drawerContainerColor = LaraColors.Surface, drawerContentColor = LaraColors.TextPrimary) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(stringResource(R.string.app_name), Modifier.padding(LaraSpacing.S4), style = MaterialTheme.typography.headlineMedium,
                    color = LaraColors.PrimaryDark)
                HomeDrawerItem(stringResource(R.string.home_classes), R.drawable.ic_books_fill, selected = true) {
                    scope.launch { drawer.close() }
                }
                Text(stringResource(if (teacher) R.string.home_teaching else R.string.home_enrolled),
                    Modifier.padding(LaraSpacing.S4), style = MaterialTheme.typography.labelLarge)
                classes.forEach { row ->
                    HomeDrawerItem(row.name, R.drawable.ic_books_regular, subtitle = row.section) {
                        scope.launch { drawer.close(); onOpenClass(row.id) }
                    }
                }
                HorizontalDivider(color = LaraColors.Border)
                onManageClasses?.let { manage ->
                    HomeDrawerItem(stringResource(R.string.classes_manage), R.drawable.ic_books_regular) {
                        scope.launch { drawer.close(); manage() }
                    }
                }
                openSettings?.let { open ->
                    HomeDrawerItem(stringResource(R.string.settings_title), R.drawable.ic_gear) {
                        scope.launch { drawer.close(); open() }
                    }
                }
            }
        }
    }) {
        Scaffold(containerColor = LaraColors.Canvas, topBar = {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = LaraSpacing.S3),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(menuIcon, stringResource(R.string.home_open_navigation))
                    }
                    Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
            }
        }, floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }, modifier = Modifier.size(PrimaryTouchTarget).laraShadow(shape = LaraShapes.large),
                containerColor = LaraColors.PrimaryLight, contentColor = LaraColors.PrimaryDark,
                elevation = FloatingActionButtonDefaults.elevation(0.dp),
                shape = LaraShapes.large) {
                Icon(plusIcon, action)
            }
        }, content = content)
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = LaraColors.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentColor = LaraColors.TextPrimary, shape = LaraShapes.extraLarge,
        scrimColor = LaraColors.TextPrimary.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            TextButton(onClick = { adding = false; onAdd() }, enabled = canAdd,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(action) }
            if (!canAdd) Text(stringResource(R.string.home_add_unavailable), Modifier.padding(LaraSpacing.S4))
            TextButton(onClick = { adding = false }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.enrollment_cancel_action))
            }
        }
    }
}

@Composable
private fun HomeDrawerItem(title: String, icon: Int, subtitle: String = "", selected: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { this.selected = selected },
        shape = LaraShapes.extraLarge, color = if (selected) LaraColors.PrimaryLight else LaraColors.Surface,
        contentColor = if (selected) LaraColors.PrimaryDark else LaraColors.TextPrimary) {
        Row(Modifier.padding(LaraSpacing.S4), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
            }
        }
    }
}
