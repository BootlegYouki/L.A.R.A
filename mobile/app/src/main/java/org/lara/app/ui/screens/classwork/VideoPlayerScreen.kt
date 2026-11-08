package org.lara.app.ui.screens.classwork

import androidx.activity.compose.BackHandler
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

@UnstableApi
@Composable
fun VideoPlayerScreen(container: AppContainer, userId: String, id: String, close: () -> Unit) {
    val model: VideoViewModel = viewModel(key = "video-$userId-$id", factory = viewModelFactory {
        initializer { VideoViewModel(container.videos, id) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val material by model.material.collectAsStateWithLifecycle()
    val download by model.download.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var position by rememberSaveable(id) { mutableLongStateOf(0) }
    var playing by rememberSaveable(id) { mutableStateOf(false) }
    BackHandler(onBack = close)
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { started = true; model.start() }
            if (event == Lifecycle.Event.ON_STOP) { started = false; model.stop() }
        }
        lifecycle.addObserver(observer)
        if (started) model.start()
        onDispose { lifecycle.removeObserver(observer); model.stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        TextButton(onClick = close, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.size(24.dp))
            Spacer(Modifier.width(LaraSpacing.S2))
            Text(stringResource(R.string.video_back))
        }
        Text(material?.title ?: stringResource(R.string.video_title), style = MaterialTheme.typography.titleLarge)
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
        val source = state.source
        if (source != null) {
            VideoPlayback(source, container::createVideoPlayer, position, playing,
                onSnapshot = { time, play -> position = time; playing = play }, onRetry = model::load)
        }
        if (source is VideoSource.Local) {
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
                Text(stringResource(R.string.video_local), color = LaraColors.PrimaryDark)
            }
        }
        DownloadControls(download, model::save, model::pause)
    }
}

@UnstableApi
@Composable
private fun VideoPlayback(source: VideoSource, create: (VideoSource) -> Player, initialPosition: Long,
    initiallyPlaying: Boolean, onSnapshot: (Long, Boolean) -> Unit, onRetry: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by remember(source) { mutableLongStateOf(initialPosition) }
    var duration by remember(source) { mutableLongStateOf(0) }
    var isPlaying by remember(source) { mutableStateOf(false) }
    var buffering by remember(source) { mutableStateOf(true) }
    var ended by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<VideoProblem?>(null) }
    var player by remember(source) { mutableStateOf<Player?>(null) }
    var scrub by remember(source) { mutableStateOf<Float?>(null) }
    DisposableEffect(source, lifecycle) {
        var current: Player? = null
        var resumePlaying = initiallyPlaying
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
                resumePlaying = it.playWhenReady
                onSnapshot(position, resumePlaying)
                it.removeListener(listener); it.release()
            }
            current = null
            player = null
        }
        fun start() {
            if (current != null) return
            try {
                val created = create(source)
                current = created
                created.addListener(listener)
                created.seekTo(position)
                created.playWhenReady = resumePlaying
                player = created
                error = null
                created.prepare()
            } catch (e: Exception) { release(); error = (e as? VideoFailure)?.problem ?: VideoProblem.UNAVAILABLE }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) release()
            if (event == Lifecycle.Event.ON_START) start()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); release() }
    }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                player?.let {
                    position = it.currentPosition.coerceAtLeast(0)
                    duration = it.duration.coerceAtLeast(0)
                    onSnapshot(position, it.playWhenReady)
                }
                delay(250)
            }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(LaraShapes.medium).background(LaraColors.TextPrimary)) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            useController = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = LaraColors.Primary)
    }
    if (buffering) Text(stringResource(R.string.video_buffering), style = MaterialTheme.typography.bodyMedium)
    error?.let {
        VideoMessage(problemText(it))
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.video_retry))
        }
    }
    if (ended) Text(stringResource(R.string.video_finished), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    val time = stringResource(R.string.video_time, timeText(position), timeText(duration))
    Text(time, style = MaterialTheme.typography.bodyMedium)
    val seekLabel = stringResource(R.string.video_seek)
    Slider(value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
        onValueChange = { scrub = it },
        onValueChangeFinished = { scrub?.let { player?.seekTo((duration * it).toLong()) }; scrub = null },
        enabled = duration > 0 && error == null,
        colors = SliderDefaults.colors(thumbColor = LaraColors.Primary, activeTrackColor = LaraColors.Primary,
            inactiveTrackColor = LaraColors.Border, activeTickColor = LaraColors.PrimaryDark,
            inactiveTickColor = LaraColors.PrimaryDark, disabledThumbColor = LaraColors.TextMuted,
            disabledActiveTrackColor = LaraColors.TextMuted, disabledInactiveTrackColor = LaraColors.Border),
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { contentDescription = seekLabel; stateDescription = time })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { player?.seekTo((position - 10_000).coerceAtLeast(0)) },
            enabled = error == null && player != null, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.video_back_ten))
        }
        FilledIconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else {
            if (ended) it.seekTo(0); it.play()
        } } }, enabled = error == null && player != null, modifier = Modifier.size(PrimaryTouchTarget)) {
            Icon(painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (isPlaying) R.string.video_pause else R.string.video_play), Modifier.size(32.dp))
        }
        OutlinedButton(onClick = { player?.seekTo((position + 10_000).coerceAtMost(duration)) },
            enabled = duration > 0 && error == null, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.video_forward_ten))
        }
    }
}

@Composable
private fun DownloadControls(state: VideoDownload, save: () -> Unit, pause: () -> Unit) {
    val active = state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.PAUSED
    val percent = if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0
    if (active || state.phase == DownloadPhase.COMPLETE) {
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
        Text(stringResource(R.string.video_progress, percent), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { percent / 100f }, color = LaraColors.Primary, trackColor = LaraColors.Border,
            modifier = Modifier.fillMaxWidth().height(LaraSpacing.S3).clip(LaraShapes.large))
    }
    if (state.phase == DownloadPhase.FAILED) Text(stringResource(R.string.video_download_failed), style = MaterialTheme.typography.titleMedium)
    state.problem?.let { VideoMessage(problemText(it)) }
    if (state.phase != DownloadPhase.COMPLETE) {
        Button(onClick = if (active) pause else save, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
            Text(stringResource(if (active) R.string.video_cancel else if (state.bytes > 0) R.string.video_resume else R.string.video_save))
        }
        if (state.phase == DownloadPhase.PAUSED) OutlinedButton(onClick = save,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.video_resume)) }
    }
}

@Composable
private fun VideoMessage(message: Int) {
    Row(Modifier.fillMaxWidth().background(LaraColors.WarningLight, LaraShapes.medium).padding(LaraSpacing.S3),
        horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
        Icon(painterResource(R.drawable.ic_warning_circle), null, Modifier.size(24.dp), tint = LaraColors.WarningDark)
        Text(stringResource(message), color = LaraColors.WarningDark, style = MaterialTheme.typography.bodyMedium)
    }
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
