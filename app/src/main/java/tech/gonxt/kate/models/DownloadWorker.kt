package tech.gonxt.kate.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tech.gonxt.kate.KateApplication
import tech.gonxt.kate.R

/**
 * Model downloads used to run inside the ViewModel scope and silently died when
 * the app was swiped away or killed mid-download. WorkManager owns them now:
 * they survive process death, show a progress notification, and resume from
 * the .part file on retry.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as KateApplication
        val id = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val spec = Models.ALL.firstOrNull { it.id == id } ?: return Result.failure()
        runCatching { setForeground(foregroundInfo(spec)) }
        app.modelManager.download(spec)
        return if (app.modelManager.isReady(spec)) {
            Result.success()
        } else if (runAttemptCount < 3) {
            Result.retry() // resume picks up the .part file
        } else {
            Result.failure()
        }
    }

    private fun foregroundInfo(spec: ModelSpec): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Athena downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val n = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Athena is downloading")
            .setContentText("${spec.displayName} · ~${spec.approxMB} MB")
            .setOngoing(true)
            .build()
        return ForegroundInfo(spec.id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "kate_downloads"

        fun enqueue(context: Context, spec: ModelSpec) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download-${spec.id}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf(KEY_MODEL_ID to spec.id))
                    .build(),
            )
        }
    }
}
