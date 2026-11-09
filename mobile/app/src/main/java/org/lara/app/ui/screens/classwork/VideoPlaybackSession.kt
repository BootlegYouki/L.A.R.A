package org.lara.app.ui.screens.classwork

import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.lara.app.domain.VideoSource

/** Keeps the decoder through Activity recreation, but releases it on a real stop or screen exit. */
@UnstableApi
class VideoPlaybackSession : ViewModel() {
    var player: Player? = null
        private set
    private var source: VideoSource? = null
    private var position = 0L
    private var playing = false
    private var view = java.lang.ref.WeakReference<PlayerView>(null)

    fun attach(next: PlayerView, current: Player?) {
        if (current == null) { next.player = null; return }
        if (view.get() !== next || next.player !== current) {
            PlayerView.switchTargetView(current, view.get(), next)
            view = java.lang.ref.WeakReference(next)
        }
    }

    fun detach(previous: PlayerView) {
        if (view.get() === previous) { previous.player = null; view.clear() }
    }

    fun start(next: VideoSource, create: (VideoSource) -> Player): Player {
        if (source != next) release()
        source = next
        return player ?: create(next).also {
            player = it
            it.seekTo(position)
            it.playWhenReady = playing
            it.prepare()
        }
    }

    fun release() {
        view.get()?.player = null
        view.clear()
        player?.let {
            position = it.currentPosition.coerceAtLeast(0)
            playing = it.playWhenReady
            it.release()
        }
        player = null
    }

    override fun onCleared() { release() }
}
