package app.haul.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.haul.HaulApp
import java.util.concurrent.TimeUnit

/** Puts a download that hit a temporary error back in the queue once its wait is over and there is a connection. */
class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, 0)
        if (id > 0) HaulApp.graph(applicationContext).queue.retryDue(id)
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private fun name(id: Long) = "retry-$id"

        fun schedule(context: Context, id: Long, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(name(id))
        }
    }
}
