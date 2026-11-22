package org.lara.app.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lara.app.LaraApp
import org.lara.app.R
import org.lara.app.data.remote.HubFailure
import org.lara.app.domain.MaterialDraft

class MaterialUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val title = inputData.getString("title").orEmpty()
        val container = (applicationContext as LaraApp).container
        var sending: File? = null
        return try {
            if (container.syncUserId() != owner) return Result.failure(inputData)
            val fileName = requireNotNull(inputData.getString("file"))
            require(fileName == File(fileName).name)
            val file = File(applicationContext.filesDir, "authoring-uploads/$fileName")
            require(file.isFile)
            val marker = File(file.parentFile, ".${file.name}.sending")
            sending = marker
            if (marker.exists()) {
                // Confirmation applies to the old request, never to a restarted copy of this one.
                if (!inputData.getBoolean("confirmed", false) || runAttemptCount > 0 || marker.readText() == id.toString()) {
                    return uncertain()
                }
                if (!marker.delete()) return uncertain()
            }
            setForeground(foreground(title))
            setProgress(workDataOf("title" to title, "percent" to 0))
            container.materialAuthoringApi.upload(owner, requireNotNull(inputData.getString("hub")),
                requireNotNull(inputData.getString("class")), file,
                MaterialDraft(title, requireNotNull(inputData.getString("type")), inputData.getString("topic"),
                    inputData.getString("assignment"), inputData.getString("announcement")), inputData.getString("hub_id"), onSending = {
                    if (isStopped) throw java.io.InterruptedIOException()
                    if (!marker.createNewFile()) throw java.io.IOException("Upload already attempted")
                    marker.writeText(id.toString())
                }, validateReceipt = true) { percent ->
                if (isStopped) throw java.io.InterruptedIOException()
                setProgressAsync(workDataOf("title" to title, "percent" to percent))
            }
            file.delete()
            marker.delete()
            container.syncScheduler.enqueueNow()
            Result.success(workDataOf("title" to title))
        } catch (error: CancellationException) { throw error }
        catch (error: HubFailure) {
            if (sending?.exists() == true && (error.status in 300..399 || error.status >= 500)) {
                return uncertain()
            }
            sending?.delete()
            if ((error.status == 429 || error.status >= 500) && runAttemptCount < 5) Result.retry()
            else Result.failure(inputData)
        } catch (_: java.io.IOException) {
            if (sending?.exists() == true) uncertain()
            else Result.retry()
        } catch (_: Exception) {
            if (sending?.exists() == true) uncertain() else Result.failure(inputData)
        }
    }

    private fun uncertain() = Result.failure(Data.Builder().putAll(inputData).putBoolean("uncertain", true).build())

    private fun foreground(title: String): ForegroundInfo {
        val channel = "classwork-upload"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, applicationContext.getString(R.string.author_upload_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_paper_plane).setContentTitle(applicationContext.getString(R.string.author_uploading))
            .setContentText(title).setOngoing(true).setProgress(0, 0, true).build()
        val id = id.hashCode() and Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }
}
