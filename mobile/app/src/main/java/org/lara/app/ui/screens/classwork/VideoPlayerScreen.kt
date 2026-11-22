package org.lara.app.ui.screens.classwork

import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
import org.lara.app.ui.components.ClassroomBackBar
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
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val notificationTitle = material?.title ?: stringResource(R.string.video_title)
    LaunchedEffect(download) { postDownloadNotification(appContext, id, notificationTitle, download) }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { model.save() }
    val save = {
        if (android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else model.save()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val playback: VideoPlaybackSession = viewModel(viewModelStoreOwner = owner, key = "video-player-$userId-$id")
    var fullscreen by rememberSaveable(id) { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable(id) { mutableStateOf(false) }
    var open by rememberSaveable(id) { mutableStateOf(false) }
    BackHandler { if (fullscreen) fullscreen = false else if (open) open = false else close() }
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
    if (confirmRemoval) org.lara.app.ui.components.NativeConfirmDialog(stringResource(R.string.video_remove), stringResource(R.string.video_remove_confirmation),
        stringResource(R.string.video_remove_confirm), stringResource(R.string.video_remove_cancel),
        { confirmRemoval = false; playback.release(); model.remove() }, { confirmRemoval = false })
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LaraColors.Surface)) {
        if (!fullscreen) ClassroomBackBar(close)
        org.lara.app.ui.components.PullRefresh({
            model.load()
            kotlinx.coroutines.withTimeoutOrNull(1_000) { model.state.first { it.loading } }
            kotlinx.coroutines.withTimeoutOrNull(15_000) { model.state.first { !it.loading } }
        }, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            if (!fullscreen) {
                Text(material?.title ?: stringResource(R.string.video_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.material_attachments), style = MaterialTheme.typography.titleMedium)
                // A frame can only be read from the saved file; a streamed video shows a dark card with a play button.
                var frame by remember(state.source) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(state.source) {
                    val path = (state.source as? VideoSource.Local)?.path
                    frame = if (path == null) null else withContext(kotlinx.coroutines.Dispatchers.IO) {
                        readVideoThumbnail(path)
                    }
                }
                val thumbnail = frame
                DisposableEffect(thumbnail) { onDispose { thumbnail?.recycle() } }
                if (state.source != null) Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AttachmentThumb(frame, R.drawable.ic_play, play = true) { open = true }
                AttachmentTitleRow(material?.title ?: stringResource(R.string.video_title),
                    R.drawable.ic_play, LaraColors.PrimaryDark) { open = true }
                }
                if (state.loading && state.source == null) {
                    Spacer(Modifier.fillMaxWidth(0.48f).aspectRatio(1.6f).clip(LaraShapes.medium).background(Color.Black))
                    Spacer(Modifier.heightIn(min = MinTouchTarget))
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
            if (!fullscreen && !(state.loading && state.source == null)) {
                if (state.source is VideoSource.Local) {
                    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                        Text(stringResource(R.string.video_local), style = MaterialTheme.typography.bodyLarge,
                            color = LaraColors.PrimaryDark)
                    }
                    OutlinedButton(onClick = { confirmRemoval = true }, enabled = !state.removing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                        Text(stringResource(R.string.video_remove))
                    }
                }
                if (state.removing) Text(stringResource(R.string.video_removing))
                else if (state.source !is VideoSource.Local) DownloadControls(download, save, model::pause)
            }
        }
        }
    }
    // The player floats over a dimmed page; tapping the dim area closes it.
    state.source?.takeIf { open }?.let { source ->
        Box(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { if (!fullscreen) open = false },
            contentAlignment = Alignment.Center) {
          Box(if (fullscreen) Modifier else Modifier.padding(horizontal = LaraSpacing.S4)) {
            VideoPlayback(source, container::createVideoPlayer, playback, fullscreen,
                onFullscreen = { fullscreen = it }, onRetry = model::load)
          }
        }
    }
    }
}

@UnstableApi
@Composable
private fun VideoPlayback(source: VideoSource, create: (VideoSource) -> Player, session: VideoPlaybackSession,
    fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onRetry: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(source) { mutableStateOf<VideoProblem?>(null) }
    var player by remember(source) { mutableStateOf<Player?>(null) }
    val activity = LocalContext.current.activity()
    DisposableEffect(source, lifecycle) {
        var current: Player? = null
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
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
            current?.removeListener(listener)
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
    // Media3's own controller: play/pause, seek bar, time and fullscreen button.
    val render: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(factory = { context -> PlayerView(context).apply {
            useController = true
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        } }, update = {
            session.attach(it, player)
            it.setFullscreenButtonState(fullscreen)
            it.setFullscreenButtonClickListener { onFullscreen(!fullscreen) }
        }, onRelease = { session.detach(it) }, modifier = modifier.background(Color.Black))
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
        error?.let {
            if (it != VideoProblem.HUB_UNAVAILABLE) VideoMessage(problemText(it))
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.video_retry))
            }
        }
    }
}

@Composable
internal fun DownloadControls(state: VideoDownload, save: () -> Unit, pause: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        val downloading = state.phase == DownloadPhase.DOWNLOADING
        if (state.phase == DownloadPhase.FAILED) Text(stringResource(R.string.video_download_failed), style = MaterialTheme.typography.titleMedium)
        if (state.phase == DownloadPhase.PAUSED && state.bytes > 0) {
            Text(stringResource(R.string.video_status_interrupted), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.problem == VideoProblem.HUB_UNAVAILABLE) {
            org.lara.app.ui.components.DiscoveryBanner(R.string.video_status_offline, warning = true, success = false)
        }
        state.problem?.takeIf { it != VideoProblem.HUB_UNAVAILABLE }?.let { VideoMessage(problemText(it)) }
        if (state.phase != DownloadPhase.COMPLETE && state.problem != VideoProblem.HUB_UNAVAILABLE) {
            OutlinedButton(onClick = if (downloading) pause else save, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
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

internal fun readVideoThumbnail(path: String): android.graphics.Bitmap? {
    if (android.os.Build.VERSION.SDK_INT < 27) return null
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
    } catch (_: RuntimeException) {
        null
    } catch (_: java.io.IOException) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
