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
    if (state.phase != DownloadPhase.DOWNLOADING && state.phase != DownloadPhase.COMPLETE) { manager.cancel(notificationId); return }
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    if (Build.VERSION.SDK_INT >= 26) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
    }
    val done = state.phase == DownloadPhase.COMPLETE
    val percent = if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0
    manager.notify(notificationId, NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_download).setContentTitle(title)
        .setContentText(context.getString(if (done) R.string.video_downloaded else R.string.video_downloading))
        .setProgress(100, percent, state.total <= 0 && !done).setOnlyAlertOnce(true).setOngoing(!done).setAutoCancel(done)
        .setSilent(true).build())
}
