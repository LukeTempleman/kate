package tech.gonxt.kate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import tech.gonxt.kate.KateApplication
import tech.gonxt.kate.MainActivity
import tech.gonxt.kate.R

/**
 * Wake-word foreground service (spec M1.3): keeps the mic loop alive with a
 * persistent notification, as Android requires for always-listening.
 */
class KateVoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Kate ears", NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "Wake-word listening" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val app = application as KateApplication
        app.voicePipeline.start(wake = app.settings.value.wakeWordEnabled)
        return START_STICKY
    }

    override fun onDestroy() {
        (application as KateApplication).voicePipeline.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Kate")
            .setContentText("Listening for “Kate”")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kate_ears"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KateVoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KateVoiceService::class.java))
        }
    }
}
