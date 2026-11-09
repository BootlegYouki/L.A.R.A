package org.lara.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.lara.app.LaraApp
import org.lara.app.data.remote.HubFailure

class HomeworkUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LaraApp).container
        val userId = container.syncUserId() ?: return Result.success()
        return try {
            val account = container.forUser(userId)
            // Pull first to reconcile the Hub identity and revoked memberships before sending photos.
            account.sync.synchronize(userId)
            account.homework.uploadQueued()
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: HubFailure) {
            if (error.status == 429 || error.status >= 500) Result.retry() else Result.failure()
        } catch (_: java.io.IOException) { Result.retry() }
        catch (_: Exception) { Result.failure() }
    }
}
