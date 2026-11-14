package org.lara.app.ui.screens.classwork

import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.*
import org.lara.app.ui.theme.*
import org.lara.app.ui.components.ClassroomDetailHeader
import org.lara.app.ui.components.ClassroomSurface
import org.lara.app.ui.components.DiscoveryBanner

@UnstableApi
@Composable
fun VideoPlayerScreen(container: AppContainer, userId: String, id: String, close: () -> Unit) {
    val activity = LocalContext.current.activity()
    // The account shell's composition-owned store is cleared on rotation.
    val owner = activity as? androidx.lifecycle.ViewModelStoreOwner
        ?: requireNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val model: VideoViewModel = viewModel(viewModelStoreOwner = owner, key = "video-$userId-$id", factory = viewModelFactory {
        initializer { VideoViewModel(container.videos, id) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val material by model.material.collectAsStateWithLifecycle()
    val download by model.download.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val playback: VideoPlaybackSession = viewModel(viewModelStoreOwner = owner, key = "video-player-$userId-$id")
    var fullscreen by rememberSaveable(id) { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable(id) { mutableStateOf(false) }
    BackHandler { if (fullscreen) fullscreen = false else close() }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { started = true; model.start() }
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) { started = false; model.stop(); playback.release() }
        }
        lifecycle.addObserver(observer)
        if (started) model.start()
        onDispose {
            lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) { model.stop(); playback.release() }
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        containerColor = LaraColors.Surface,
        titleContentColor = LaraColors.TextPrimary,
        textContentColor = LaraColors.TextSecondary,
        title = { Text(stringResource(R.string.video_remove)) },
        text = { Text(stringResource(R.string.video_remove_confirmation), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            confirmRemoval = false
            playback.release()
            model.remove()
        }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.video_remove_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.video_remove_cancel))
        } })
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) ClassroomDetailHeader(material?.title ?: stringResource(R.string.video_title), R.string.video_back, close)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (!fullscreen) {
                if (state.loading) {
                    Text(stringResource(R.string.video_buffering))
                    Spacer(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(LaraColors.Border, LaraShapes.medium))
                }
                state.problem?.let { problem ->
                    if (problem == VideoProblem.UNAVAILABLE) {
                        Surface(Modifier.size(80.dp), color = LaraColors.PrimaryLight, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_play), null, Modifier.size(40.dp), tint = LaraColors.PrimaryDark)
                            }
                        }
                        Text(stringResource(R.string.video_empty_title), style = MaterialTheme.typography.titleMedium)
                    }
                    VideoMessage(problemText(problem))
                    OutlinedButton(onClick = model::load, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(stringResource(R.string.video_retry))
                    }
                }
            }
            state.source?.let { source ->
                VideoPlayback(source, container::createVideoPlayer, playback, fullscreen,
                    onFullscreen = { fullscreen = it }, onRetry = model::load)
            }
            if (!fullscreen) {
                if (state.source is VideoSource.Local) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                        Text(stringResource(R.string.video_local), color = LaraColors.PrimaryDark)
                    }
                    OutlinedButton(onClick = { confirmRemoval = true }, enabled = !state.removing,
                        modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_trash), null, Modifier.size(24.dp))
                        Spacer(Modifier.width(LaraSpacing.S2))
                        Text(stringResource(R.string.video_remove))
                    }
                }
                if (state.removing) Text(stringResource(R.string.video_removing))
                else DownloadControls(download, model::save, model::pause)
            }
        }
    }
}

@UnstableApi
@Composable
private fun VideoPlayback(source: VideoSource, create: (VideoSource) -> Player, session: VideoPlaybackSession,
    fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onRetry: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by remember(source) { mutableLongStateOf(session.player?.currentPosition ?: 0) }
    var duration by remember(source) { mutableLongStateOf(0) }
    var isPlaying by remember(source) { mutableStateOf(false) }
    var buffering by remember(source) { mutableStateOf(true) }
    var ended by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<VideoProblem?>(null) }
    var player by remember(source) { mutableStateOf<Player?>(null) }
    var scrub by remember(source) { mutableStateOf<Float?>(null) }
    val activity = LocalContext.current.activity()
    DisposableEffect(source, lifecycle) {
        var current: Player? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
            }
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayerError(exception: PlaybackException) {
                buffering = false
                var cause: Throwable? = exception
                var problem = if (exception.errorCode >= 3000) VideoProblem.INVALID_FILE else VideoProblem.HUB_UNAVAILABLE
                while (cause != null) {
                    if (cause is VideoFailure) { problem = cause.problem; break }
                    cause = cause.cause
                }
                error = if (source is VideoSource.Local) VideoProblem.INVALID_FILE else problem
            }
        }
        fun release() {
            current?.let {
                position = it.currentPosition.coerceAtLeast(0)
                it.removeListener(listener)
            }
            session.release()
            current = null
            player = null
        }
        fun start() {
            if (current != null) return
            try {
                val created = session.start(source, create)
                current = created
                created.addListener(listener)
                player = created
                error = null
                isPlaying = created.isPlaying
                buffering = created.playbackState == Player.STATE_BUFFERING
                ended = created.playbackState == Player.STATE_ENDED
            } catch (e: Exception) { release(); error = (e as? VideoFailure)?.problem ?: VideoProblem.UNAVAILABLE }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) release()
            if (event == Lifecycle.Event.ON_START) start()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            current?.removeListener(listener)
            if (activity?.isChangingConfigurations != true) release()
        }
    }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                player?.let {
                    position = it.currentPosition.coerceAtLeast(0)
                    duration = it.duration.coerceAtLeast(0)
                }
                delay(250)
            }
        }
    }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val accessibility = LocalAccessibilityManager.current
    fun interact() { controls = true; interaction++ }
    LaunchedEffect(fullscreen, isPlaying, scrub, interaction) {
        controls = true
        if (fullscreen && isPlaying && scrub == null) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3_000, containsIcons = true,
                containsText = true, containsControls = true) ?: 3_000)
            controls = false
        }
    }
    val transport: @Composable () -> Unit = {
        val time = stringResource(R.string.video_time, timeText(position), timeText(duration))
        Text(time, style = MaterialTheme.typography.bodyMedium)
        val seekLabel = stringResource(R.string.video_seek)
        Slider(value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { interact(); scrub = it },
            onValueChangeFinished = { scrub?.let { player?.seekTo((duration * it).toLong()) }; scrub = null; interact() },
            enabled = duration > 0 && error == null,
            colors = SliderDefaults.colors(thumbColor = LaraColors.Primary, activeTrackColor = LaraColors.Primary,
                inactiveTrackColor = LaraColors.Border, activeTickColor = LaraColors.PrimaryDark,
                inactiveTickColor = LaraColors.PrimaryDark, disabledThumbColor = LaraColors.TextMuted,
                disabledActiveTrackColor = LaraColors.TextMuted, disabledInactiveTrackColor = LaraColors.Border),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { contentDescription = seekLabel; stateDescription = time })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            if (!fullscreen) OutlinedButton(onClick = { interact(); player?.seekTo((position - 10_000).coerceAtLeast(0)) },
                enabled = error == null && player != null, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.video_back_ten))
            }
            FilledIconButton(onClick = { interact(); player?.let { if (it.isPlaying) it.pause() else {
                if (ended) it.seekTo(0); it.play()
            } } }, enabled = error == null && player != null, modifier = Modifier.size(PrimaryTouchTarget)) {
                Icon(painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (isPlaying) R.string.video_pause else R.string.video_play), Modifier.size(32.dp))
            }
            if (!fullscreen) OutlinedButton(onClick = { interact(); player?.seekTo((position + 10_000).coerceAtMost(duration)) },
                enabled = duration > 0 && error == null, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.video_forward_ten))
            }
            if (fullscreen) IconButton(onClick = { interact(); onFullscreen(false) }, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_corners_in), stringResource(R.string.video_exit_fullscreen),
                    Modifier.size(24.dp))
            }
        }
    }
    val render: @Composable (Modifier) -> Unit = { modifier ->
        val showControls = stringResource(R.string.video_show_controls)
        Box(modifier.background(Color.Black).semantics { if (fullscreen) contentDescription = showControls }
            .clickable(onClickLabel = showControls) { interact() }) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } }, update = { session.attach(it, player) }, onRelease = { session.detach(it) }, modifier = Modifier.fillMaxSize())
            if (!fullscreen) IconButton(onClick = { onFullscreen(true) },
                modifier = Modifier.align(Alignment.TopEnd).padding(LaraSpacing.S2).size(MinTouchTarget)
                    .background(LaraColors.TextPrimary, CircleShape)) {
                Icon(painterResource(R.drawable.ic_corners_out), stringResource(R.string.video_fullscreen),
                    Modifier.size(24.dp), tint = LaraColors.Surface)
            }
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = LaraColors.Primary)
            if (fullscreen && controls) {
                Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().fillMaxWidth()
                    .background(LaraColors.TextPrimary).padding(LaraSpacing.S3)) {
                    error?.let { VideoMessage(problemText(it)) }
                    CompositionLocalProvider(LocalContentColor provides LaraColors.Surface) { transport() }
                }
            }
        }
    }
    if (fullscreen) {
        val context = LocalContext.current
        val composition = rememberCompositionContext()
        val currentRender by rememberUpdatedState(render)
        val exit by rememberUpdatedState({ onFullscreen(false) })
        val view = remember(context) { ComposeView(context).apply {
            setParentCompositionContext(composition)
            setContent { currentRender(Modifier.fillMaxSize()) }
        } }
        // Compose 1.7 Dialog measures against screen dp excluding bars, leaving a page border.
        val dialog = remember(context) { ComponentDialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener { exit() }
            setContentView(view)
        } }
        DisposableEffect(dialog, lifecycle) {
                val window = requireNotNull(dialog.window)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                dialog.show()
                window.setBackgroundDrawableResource(android.R.color.black)
                if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                val previousBehavior = controller.systemBarsBehavior
                val lightStatus = controller.isAppearanceLightStatusBars
                val lightNavigation = controller.isAppearanceLightNavigationBars
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
                val status = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
                val navigation = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
                fun hide() {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.isAppearanceLightStatusBars = false
                    controller.isAppearanceLightNavigationBars = false
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                fun restore() {
                    controller.systemBarsBehavior = previousBehavior
                    controller.isAppearanceLightStatusBars = lightStatus
                    controller.isAppearanceLightNavigationBars = lightNavigation
                    if (status) controller.show(WindowInsetsCompat.Type.statusBars())
                    if (navigation) controller.show(WindowInsetsCompat.Type.navigationBars())
                }
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) hide()
                    if (event == Lifecycle.Event.ON_STOP) restore()
                }
                lifecycle.addObserver(observer)
                hide()
                onDispose {
                    lifecycle.removeObserver(observer)
                    restore()
                    dialog.setOnDismissListener(null)
                    dialog.dismiss()
                    view.disposeComposition()
                }
        }
    } else {
        val configuration = LocalConfiguration.current
        val viewport = if (configuration.screenWidthDp > configuration.screenHeightDp)
            Modifier.height((configuration.screenHeightDp * 0.45f).dp) else Modifier.aspectRatio(16f / 9f)
        render(Modifier.fillMaxWidth().then(viewport).clip(LaraShapes.medium))
        if (buffering) Text(stringResource(R.string.video_buffering), style = MaterialTheme.typography.bodyMedium)
        error?.let {
            VideoMessage(problemText(it))
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.video_retry))
            }
        }
        if (ended) Text(stringResource(R.string.video_finished), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        transport()
    }
}

@Composable
internal fun DownloadControls(state: VideoDownload, save: () -> Unit, pause: () -> Unit) {
    ClassroomSurface {
        Text(stringResource(R.string.video_home_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.video_home_help), style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
        val downloading = state.phase == DownloadPhase.DOWNLOADING
        val percent = if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0
        if (downloading || state.phase == DownloadPhase.PAUSED || state.phase == DownloadPhase.COMPLETE) {
            val label = when (state.phase) {
                DownloadPhase.DOWNLOADING -> R.string.video_downloading
                DownloadPhase.COMPLETE -> R.string.video_downloaded
                else -> R.string.video_paused
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (state.phase == DownloadPhase.COMPLETE) R.drawable.ic_check_circle else R.drawable.ic_download),
                    null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(stringResource(R.string.video_progress, percent), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { percent / 100f }, color = LaraColors.Primary, trackColor = LaraColors.Border,
                modifier = Modifier.fillMaxWidth().height(LaraSpacing.S3).clip(LaraShapes.large))
        }
        if (state.phase == DownloadPhase.FAILED) Text(stringResource(R.string.video_download_failed), style = MaterialTheme.typography.titleMedium)
        state.problem?.let { VideoMessage(problemText(it)) }
        if (state.phase != DownloadPhase.COMPLETE) {
            Button(onClick = if (downloading) pause else save, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Text(stringResource(if (downloading) R.string.video_cancel
                    else if (state.phase == DownloadPhase.PAUSED || state.bytes > 0) R.string.video_resume else R.string.video_save))
            }
        }
    }
}

@Composable
private fun VideoMessage(message: Int) {
    DiscoveryBanner(message, warning = true, success = false)
}

private fun problemText(problem: VideoProblem): Int = when (problem) {
    VideoProblem.SIGN_IN -> R.string.video_sign_in
    VideoProblem.STORAGE -> R.string.video_storage
    VideoProblem.INVALID_FILE -> R.string.video_invalid
    VideoProblem.CHANGED -> R.string.video_changed
    VideoProblem.UNAVAILABLE -> R.string.video_unavailable
    VideoProblem.HUB_UNAVAILABLE -> R.string.video_offline
}
internal fun timeText(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
