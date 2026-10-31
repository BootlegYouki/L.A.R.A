package org.lara.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    content: @Composable (ShellDestination) -> Unit = { ShellPlaceholder(it) },
) {
    require(state.role == ShellRole.TEACHER)
    RoleNavGraph(state, teacher = true, content)
}

@Composable
private fun RoleNavGraph(
    state: ShellState,
    teacher: Boolean,
    content: @Composable (ShellDestination) -> Unit,
    learnerClassActions: @Composable () -> Unit = {},
) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val selected = when {
        entry?.destination?.hasRoute<Classwork>() == true -> ShellDestination.CLASSWORK
        entry?.destination?.hasRoute<Quizzes>() == true -> ShellDestination.QUIZZES
        entry?.destination?.hasRoute<Tutor>() == true -> ShellDestination.AI_TUTOR
        entry?.destination?.hasRoute<People>() == true -> null
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
        containerColor = LaraColors.Canvas,
        topBar = {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Text(
                    stringResource(if (teacher) R.string.teacher_classroom else R.string.learner_classroom),
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp)
                        .padding(LaraSpacing.S4),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (state.hasInProgressAttempt) {
                    Text(stringResource(R.string.ai_quiz_locked), Modifier.padding(LaraSpacing.S4))
                }
                BottomNavBar(selected, state.hasInProgressAttempt, onSelect = { destination ->
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
                    DiscoveryBanner(R.string.shell_offline, warning = true, success = false)
                }
            }
            if (!teacher) learnerClassActions()
            if (teacher) {
                Row(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S4), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    TextButton(onClick = { controller.navigate(People) { launchSingleTop = true } }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.nav_people))
                    }
                    TextButton(onClick = { controller.navigate(Grades) { launchSingleTop = true } }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.nav_grades))
                    }
                }
            }
            NavHost(controller, startDestination = Stream, modifier = Modifier.weight(1f)) {
                composable<Stream> { TabContent(ShellDestination.STREAM, state, content) }
                composable<Classwork> { TabContent(ShellDestination.CLASSWORK, state, content) }
                composable<Quizzes> { TabContent(ShellDestination.QUIZZES, state, content) }
                composable<Tutor> { TabContent(ShellDestination.AI_TUTOR, state, content) }
                if (teacher) {
                    composable<People> { ManagementPlaceholder(R.string.nav_people, state.isLoading) }
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
