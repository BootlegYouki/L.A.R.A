package org.lara.app.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.lara.app.data.remote.EnrollmentApi
import org.lara.app.data.remote.MediaHttp
import org.lara.app.domain.VideoSource

@UnstableApi
class VideoPlayers(private val context: Context, private val api: EnrollmentApi, private val owner: String) {
    fun create(source: VideoSource): ExoPlayer {
        val uri: Uri
        val factory: DataSource.Factory
        when (source) {
            is VideoSource.Local -> {
                uri = Uri.fromFile(java.io.File(source.path))
                factory = DataSource.Factory { FileDataSource() }
            }
            is VideoSource.Hub -> {
                // Player creation uses only the saved endpoint; socket work happens on Media3's loader.
                val remote = MediaHttp(api, owner).stream(source.materialId, source.size)
                uri = remote.first
                factory = remote.second
            }
        }
        return ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 10_000, 500, 1_000)
                .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                setMediaItem(MediaItem.fromUri(uri))
            }
    }
}
