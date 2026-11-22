package org.lara.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.navigation.toRoute
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import org.lara.app.R
import org.lara.app.ui.screens.settings.LocalOpenSettings
import org.lara.app.ui.theme.*

data class HomeClass(val id: String, val name: String, val section: String, val badge: Int = 0)

// Phosphor Regular list and plus paths (256 viewport, MIT; see design-system/assets/phosphor).
private fun homeIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 16f, strokeLineCap = StrokeCap.Round, pathBuilder = draw)
    }.build()
internal val menuIcon = homeIcon("List") {
    moveTo(40f, 64f); horizontalLineTo(216f)
    moveTo(40f, 128f); horizontalLineTo(216f)
    moveTo(40f, 192f); horizontalLineTo(216f)
}
private val plusIcon = homeIcon("Plus") {
    moveTo(40f, 128f); horizontalLineTo(216f)
    moveTo(128f, 40f); verticalLineTo(216f)
}

/** Opens the class drawer; lets a class screen show the same hamburger as the home screen. */
/** A full-screen page drawn over the whole class screen (bar and bottom navigation included), without a dialog window. */
val LocalFullScreenOverlay = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

val LocalOpenDrawer = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassHomeScaffold(
    classes: List<HomeClass>,
    teacher: Boolean,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onOpenClass: (String) -> Unit,
    onJoinRequests: (() -> Unit)? = null,
    joinRequestCount: Int = 0,
    pageTitle: String? = null,
    offline: Boolean? = null,
    inClass: Boolean = false,
    onHome: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openSettings = LocalOpenSettings.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var notifications by rememberSaveable { mutableStateOf(false) }
    val brand = androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(stringResource(R.string.app_name)) }
        append(" " + stringResource(if (teacher) R.string.role_teacher else R.string.role_student))
    }
    val action = stringResource(if (teacher) R.string.classes_create else R.string.enrollment_join_title)
    val overlay = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(notifications && !drawer.isOpen) { notifications = false }
    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, scrimColor = LaraColors.TextPrimary.copy(alpha = 0.5f), drawerContent = {
        ModalDrawerSheet(Modifier.width(280.dp), drawerContainerColor = LaraColors.Surface, drawerContentColor = LaraColors.TextPrimary) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = LaraSpacing.S4, end = LaraSpacing.S4, bottom = LaraSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                // Same height as the top bar title: centred in a 64dp row below the status bar.
                Box(Modifier.fillMaxWidth().height(AppBarHeight).padding(start = LaraSpacing.S4), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                    HomeBrand(brand)
                }
                HomeDrawerItem(stringResource(R.string.home_classes), R.drawable.ic_books_fill, selected = !notifications && pageTitle == null) {
                    scope.launch { drawer.close() }; notifications = false; onHome()
                }
                HomeDrawerItem(stringResource(R.string.home_notifications), R.drawable.ic_bell, selected = notifications) {
                    scope.launch { drawer.close() }; notifications = true; onHome()
                }
                onJoinRequests?.let { HomeDrawerItem(stringResource(R.string.home_join_requests), R.drawable.ic_users_regular, selected = pageTitle != null && !notifications, badge = joinRequestCount) { scope.launch { drawer.close() }; notifications = false; it() } }
                Text(stringResource(if (teacher) R.string.home_teaching else R.string.home_enrolled),
                    Modifier.padding(start = LaraSpacing.S4, top = LaraSpacing.S4, end = LaraSpacing.S4, bottom = LaraSpacing.S1), style = MaterialTheme.typography.titleSmall)
                classes.forEach { row ->
                    HomeDrawerItem(row.name, R.drawable.ic_books_regular, subtitle = row.section, initial = row.name.take(1).uppercase(), badge = row.badge) {
                        scope.launch { drawer.close(); notifications = false; onOpenClass(row.id) }
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
        Scaffold(containerColor = LaraColors.Canvas, contentWindowInsets = if (inClass) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets, topBar = {
            if (!inClass) Surface(color = LaraColors.Canvas, contentColor = LaraColors.TextPrimary) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(AppBarHeight).padding(horizontal = LaraSpacing.S3),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(menuIcon, stringResource(R.string.home_open_navigation))
                    }
                    if (pageTitle == null) {
                        HomeBrand(brand, Modifier.weight(1f))
                    } else {
                        Text(pageTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = LaraColors.PrimaryDark)
                    }
                    if (offline != null) Icon(painterResource(if (offline) R.drawable.ic_wifi_slash else R.drawable.ic_wifi_high),
                        stringResource(if (offline) R.string.home_offline else R.string.home_connected), Modifier.padding(end = LaraSpacing.S2).size(24.dp),
                        tint = if (offline) LaraColors.WarningDark else LaraColors.Primary)
                }
            }
        }, floatingActionButton = {
            if (!notifications && !inClass && pageTitle == null) FloatingActionButton(onClick = { if (canAdd) onAdd() else adding = true }, modifier = Modifier.size(PrimaryTouchTarget).laraShadow(shape = LaraShapes.large),
                containerColor = LaraColors.PrimaryLight, contentColor = LaraColors.PrimaryDark,
                elevation = FloatingActionButtonDefaults.elevation(0.dp),
                shape = LaraShapes.large) {
                Icon(plusIcon, action)
            }
        }) { insets ->
            if (notifications) EmptyState(R.drawable.ic_bell, stringResource(R.string.notifications_empty), Modifier.padding(insets))
            else CompositionLocalProvider(LocalOpenDrawer provides { scope.launch { drawer.open() } }, LocalFullScreenOverlay provides overlay) { content(insets) }
        }
    }
    overlay.value?.let { page -> ClassroomOverlay(page) }
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
private fun HomeDrawerItem(title: String, icon: Int, subtitle: String = "", initial: String? = null, selected: Boolean = false, badge: Int = 0, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { this.selected = selected },
        shape = LaraShapes.extraLarge, color = if (selected) LaraColors.PrimaryLight else LaraColors.Surface,
        contentColor = if (selected) LaraColors.PrimaryDark else LaraColors.TextPrimary) {
        Row(Modifier.padding(LaraSpacing.S4), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            // Classes get a letter avatar like Classroom; other entries keep their icon.
            if (initial != null) Surface(Modifier.size(24.dp), shape = CircleShape, color = LaraColors.PrimaryLight) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text(initial, style = MaterialTheme.typography.labelMedium, color = LaraColors.PrimaryDark) }
            } else Icon(painterResource(icon), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
            }
            if (badge > 0) Surface(shape = CircleShape, color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Text(badge.toString(), Modifier.defaultMinSize(24.dp, 24.dp).padding(horizontal = 6.dp).wrapContentHeight(), style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Centered empty state: a tinted icon disc, a title and an optional hint. */
@Composable
fun EmptyState(icon: Int, title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier.fillMaxSize().padding(LaraSpacing.S4), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3, androidx.compose.ui.Alignment.CenterVertically)) {
        Box(Modifier.size(96.dp).background(LaraColors.PrimaryLight, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(48.dp), tint = LaraColors.PrimaryDark)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@kotlinx.serialization.Serializable private data object ClassListRoute
@kotlinx.serialization.Serializable private data class ClassRoute(val id: String)

/**
 * Class list -> class as a real back stack (system back pops it), with no transition animation.
 * [content] gets null for the list or the opened class id, plus `open` and `home` to move between them.
 */
@Composable
fun ClassNav(content: @Composable (selectedId: String?, open: (String) -> Unit, home: () -> Unit) -> Unit) {
    val nav = androidx.navigation.compose.rememberNavController()
    val open: (String) -> Unit = { id -> nav.navigate(ClassRoute(id)) { popUpTo<ClassListRoute>(); launchSingleTop = true } }
    val home: () -> Unit = { nav.popBackStack(ClassListRoute, inclusive = false) }
    androidx.navigation.compose.NavHost(nav, startDestination = ClassListRoute, modifier = Modifier.fillMaxSize(),
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
        composable<ClassListRoute> { content(null, open, home) }
        composable<ClassRoute> { content(it.toRoute<ClassRoute>().id, open, home) }
    }
}

/** A class screen: opaque so the list never shows through while sliding, and hosts the full-screen overlay slot. No drawer in here, the drawer belongs to the list. */
@Composable
fun ClassScreen(content: @Composable () -> Unit) {
    val overlay = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    Box(Modifier.fillMaxSize().background(LaraColors.Canvas)) {
        CompositionLocalProvider(LocalFullScreenOverlay provides overlay) { content() }
        overlay.value?.let { page -> ClassroomOverlay(page) }
    }
}

@Composable
private fun ClassroomOverlay(page: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val owner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle = lifecycleOwner.lifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
    }
    // The overlay is outside the tab NavHost and receives Back before the tab stack.
    BackHandler { owner.onBackPressedDispatcher.onBackPressed() }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
        Box(Modifier.fillMaxSize()) { page() }
    }
}

/** Swipe down to reload. Runs [refresh] under the system pull indicator; a failure (Hub unreachable) just ends the spinner and the cached content stays. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(refresh: suspend () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberPullToRefreshState()
    PullToRefreshBox(isRefreshing = refreshing, modifier = modifier, state = state, onRefresh = {
        refreshing = true
        scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try { refresh() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (_: Exception) {}
            finally {
                // A refresh that ends within a frame never shows as "refreshing", so the indicator stays where the finger left it.
                kotlinx.coroutines.delay((600 - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                refreshing = false
                state.animateToHidden()
            }
        }
    }, indicator = {
        PullToRefreshDefaults.Indicator(state, refreshing, Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            containerColor = LaraColors.Surface, color = LaraColors.Primary)
    }) { content() }
}

@Composable
private fun HomeBrand(title: AnnotatedString, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2),
    ) {
        Image(painterResource(R.drawable.lara_logo), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = LaraColors.PrimaryDark)
    }
}
