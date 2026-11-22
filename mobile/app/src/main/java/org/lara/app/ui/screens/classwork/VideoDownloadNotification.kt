package org.lara.app.ui.screens.classwork

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.lara.app.R
import org.lara.app.domain.DownloadPhase
import org.lara.app.domain.VideoDownload

private const val CHANNEL = "video_download"

/** Shows the save-for-home progress as the system's own progress notification instead of an in-page bar. */
@SuppressLint("MissingPermission")
internal fun postDownloadNotification(context: Context, id: String, title: String, state: VideoDownload) {
    val manager = NotificationManagerCompat.from(context)
    val notificationId = id.hashCode()
    if (state.phase == DownloadPhase.IDLE) { manager.cancel(notificationId); return }
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    if (Build.VERSION.SDK_INT >= 26) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
    }
    val active = state.phase == DownloadPhase.DOWNLOADING
    val status = when (state.phase) {
        DownloadPhase.DOWNLOADING -> R.string.video_downloading
        DownloadPhase.COMPLETE -> R.string.video_downloaded
        DownloadPhase.PAUSED -> if (state.problem == org.lara.app.domain.VideoProblem.HUB_UNAVAILABLE)
            R.string.video_status_offline else R.string.video_status_interrupted
        DownloadPhase.FAILED -> R.string.video_download_failed
        DownloadPhase.IDLE -> R.string.video_downloading
    }
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_download).setContentTitle(title)
        .setContentText(context.getString(status))
        .setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active).setSilent(true)
    if (active) notification.setProgress(100, downloadNotificationPercent(state), state.total <= 0)
    manager.notify(notificationId, notification.build())
}

/** Keep received bytes distinct from a completed, persisted download. */
internal fun downloadNotificationPercent(state: VideoDownload): Int = when (state.phase) {
    DownloadPhase.COMPLETE -> 100
    DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED, DownloadPhase.FAILED -> if (state.total <= 0) 0 else
        (state.bytes.coerceAtLeast(0) * 100 / state.total).toInt().coerceIn(0, 99)
    DownloadPhase.IDLE -> 0
}
