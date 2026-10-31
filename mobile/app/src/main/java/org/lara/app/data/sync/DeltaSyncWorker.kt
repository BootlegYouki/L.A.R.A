package org.lara.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.lara.app.LaraApp
import org.lara.app.data.remote.HubFailure

class DeltaSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LaraApp).container
        val userId = container.syncUserId() ?: return Result.success()
        if (container.endpoints.load() == null) return Result.success()
        return try {
            container.sync.synchronize(userId)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: HubFailure) {
            if (e.status == 429 || e.status >= 500) Result.retry()
            else Result.failure(workDataOf("requires_sign_in" to (e.status == 401)))
        } catch (_: java.io.IOException) { Result.retry() }
        catch (_: Exception) { Result.failure() }
    }
}
