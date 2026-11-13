package org.lara.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable
import org.lara.app.R
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.BottomNavBar
import org.lara.app.ui.components.label
import org.lara.app.ui.theme.*

@Serializable
private data object Stream
@Serializable
private data object Classwork
@Serializable
private data object Quizzes
@Serializable
private data object Tutor

private val ShellDestination.route: Any
    get() = when (this) {
        ShellDestination.STREAM -> Stream
        ShellDestination.CLASSWORK -> Classwork
        ShellDestination.PEOPLE -> People
        ShellDestination.QUIZZES -> Quizzes
        ShellDestination.AI_TUTOR -> Tutor
    }

@Serializable
private data object People

@Serializable
private data object Grades

@Composable
fun StudentNavGraph(
    state: ShellState,
    classActions: @Composable () -> Unit = {},
    content: @Composable (ShellDestination) -> Unit = { ShellPlaceholder(it) },
) {
    require(state.role == ShellRole.STUDENT)
    RoleNavGraph(state, teacher = false, content, learnerClassActions = classActions)
}

@Composable
fun TeacherNavGraph(
    state: ShellState,
    approvalActions: @Composable () -> Unit = {},
    content: @Composable (ShellDestination) -> Unit = { ShellPlaceholder(it) },
    peopleContent: (@Composable (() -> Unit) -> Unit)? = null,
) {
    require(state.role == ShellRole.TEACHER)
    RoleNavGraph(state, teacher = true, content = content, approvalActions = approvalActions, peopleContent = peopleContent)
}

@Composable
private fun RoleNavGraph(
    state: ShellState,
    teacher: Boolean,
    content: @Composable (ShellDestination) -> Unit,
    approvalActions: @Composable () -> Unit = {},
    learnerClassActions: @Composable () -> Unit = {},
    peopleContent: (@Composable (() -> Unit) -> Unit)? = null,
) {
    val controller = rememberNavController()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val entry by controller.currentBackStackEntryAsState()
    val selected = when {
        entry?.destination?.hasRoute<Classwork>() == true -> ShellDestination.CLASSWORK
        entry?.destination?.hasRoute<Quizzes>() == true -> ShellDestination.QUIZZES
        entry?.destination?.hasRoute<Tutor>() == true -> ShellDestination.AI_TUTOR
        entry?.destination?.hasRoute<People>() == true -> ShellDestination.PEOPLE
        entry?.destination?.hasRoute<Grades>() == true -> null
        else -> ShellDestination.STREAM
    }
    // Replace a restored AI route as well as an AI screen open when an attempt begins.
    LaunchedEffect(state.hasInProgressAttempt, selected) {
        if (selected != null && !state.allows(selected)) {
            controller.navigate(Quizzes) {
                popUpTo(controller.graph.id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = LaraColors.Canvas,
        topBar = {
            val classroom = org.lara.app.ui.components.LocalClassChrome.current
            org.lara.app.ui.components.ClassroomTopBar(classroom?.name
                ?: stringResource(if (teacher) R.string.teacher_classroom else R.string.learner_classroom),
                classroom?.section, classroom?.back) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_dots_three_vertical), stringResource(R.string.class_actions))
                    }
                    DropdownMenu(expanded, { expanded = false }, containerColor = LaraColors.Surface,
                        shape = LaraShapes.large) {
                        listOf(ShellDestination.QUIZZES, ShellDestination.AI_TUTOR).forEach { destination ->
                            DropdownMenuItem(text = { Text(stringResource(destination.label),
                                color = if (!state.allows(destination)) androidx.compose.ui.graphics.Color.Unspecified
                                    else if (destination == ShellDestination.AI_TUTOR) LaraColors.AiDark else LaraColors.TextPrimary) },
                                leadingIcon = { Icon(painterResource(if (destination == ShellDestination.AI_TUTOR)
                                    R.drawable.ic_sparkle_regular else R.drawable.ic_exam_regular), null,
                                    tint = if (!state.allows(destination)) LocalContentColor.current
                                        else if (destination == ShellDestination.AI_TUTOR) LaraColors.AiDark else LaraColors.PrimaryDark) },
                                enabled = state.allows(destination), modifier = Modifier.heightIn(min = MinTouchTarget),
                                onClick = { expanded = false; controller.navigate(destination.route) { launchSingleTop = true } })
                        }
                        if (state.hasInProgressAttempt) DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_quiz_locked)) }, onClick = {}, enabled = false)
                        if (teacher) HorizontalDivider(color = LaraColors.Border)
                        if (teacher) DropdownMenuItem(
                            leadingIcon = { Icon(painterResource(R.drawable.ic_exam_regular), null, tint = LaraColors.PrimaryDark) },
                            text = { Text(stringResource(R.string.nav_grades)) },
                            modifier = Modifier.heightIn(min = MinTouchTarget),
                            onClick = { expanded = false; controller.navigate(Grades) { launchSingleTop = true } })
                    }
                }
            }
        },
        bottomBar = {
            if (!keyboardOpen) Column(Modifier.navigationBarsPadding()) {
                if (state.hasInProgressAttempt) {
                    Text(stringResource(R.string.ai_quiz_locked), Modifier.padding(LaraSpacing.S4))
                }
                BottomNavBar(selected, onSelect = { destination ->
                    if (state.allows(destination)) {
                        controller.navigate(destination.route) {
                            popUpTo(controller.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                })
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (!state.isHubReachable) {
                Box(Modifier.fillMaxWidth().padding(LaraSpacing.S4)) {
                    if (keyboardOpen) Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(24.dp))
                        Text(stringResource(R.string.settings_disconnected), style = MaterialTheme.typography.bodyMedium)
                    }
                    else DiscoveryBanner(R.string.shell_offline, warning = true, success = false)
                }
            }
            if (!teacher) learnerClassActions()
            if (teacher && selected == ShellDestination.PEOPLE) {
                Box(if (keyboardOpen) Modifier.height(0.dp).clipToBounds() else Modifier) {
                    approvalActions()
                }
            }
            NavHost(controller, startDestination = Stream, modifier = Modifier.weight(1f)) {
                composable<Stream> { TabContent(ShellDestination.STREAM, state, content) }
                composable<Classwork> { TabContent(ShellDestination.CLASSWORK, state, content) }
                composable<Quizzes> { TabContent(ShellDestination.QUIZZES, state, content) }
                composable<Tutor> { TabContent(ShellDestination.AI_TUTOR, state, content) }
                composable<People> {
                    if (teacher && peopleContent != null) peopleContent { controller.popBackStack() }
                    else TabContent(ShellDestination.PEOPLE, state, content)
                }
                if (teacher) {
                    composable<Grades> { ManagementPlaceholder(R.string.nav_grades, state.isLoading) }
                }
            }
        }
    }
}

@Composable
internal fun ShellPlaceholder(destination: ShellDestination) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(destination.label), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(if (destination == ShellDestination.AI_TUTOR) R.string.shell_ai_unavailable else R.string.shell_empty), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ManagementPlaceholder(title: Int, loading: Boolean) {
    if (loading) ShellSkeleton() else Column(Modifier.padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.shell_empty))
    }
}

@Composable
private fun ShellSkeleton() {
    val loading = stringResource(R.string.shell_loading)
    Column(Modifier.fillMaxSize().padding(LaraSpacing.S4).semantics { contentDescription = loading }, verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        repeat(3) {
            Spacer(Modifier.fillMaxWidth().height(64.dp).background(LaraColors.SurfaceSubtle, LaraShapes.medium))
        }
    }
}

@Composable
private fun TabContent(
    destination: ShellDestination,
    state: ShellState,
    content: @Composable (ShellDestination) -> Unit,
) {
    when {
        !state.allows(destination) -> Text(stringResource(R.string.ai_quiz_locked), Modifier.padding(LaraSpacing.S4))
        state.isLoading -> ShellSkeleton()
        else -> content(destination)
    }
}
