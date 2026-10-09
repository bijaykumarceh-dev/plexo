package com.plexo.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {
    inner class LocalBinder : Binder() {
        val engine: DownloadEngine get() = this@DownloadService.engine
        fun start(probe: ProbeInfo, networks: List<AndroidNetwork>, streams: Int) {
            startForeground(NOTIFICATION_ID, notification("Preparing download"))
            engine.start(probe, networks, streams)
        }
        fun resume() {
            startForeground(NOTIFICATION_ID, notification("Resuming download"))
            engine.resume()
        }
    }

    private val binder = LocalBinder()
    private lateinit var engine: DownloadEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(applicationContext)
        createNotificationChannel()
        serviceScope.launch {
            engine.state.collectLatest { state ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification(
                    when (state.status) {
                        "downloading" -> "Downloading ${state.fileName}"
                        "paused" -> "Download paused"
                        "completed" -> "Download complete"
                        "error" -> state.error ?: "Download failed"
                        else -> "Plexo is ready"
                    }, state.bytesDownloaded, state.totalBytes, state.status == "paused"
                ))
                if (state.status != "downloading") stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Plexo is ready"))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(
        message: String,
        downloaded: Long = 0,
        total: Long = 0,
        indeterminate: Boolean = false
    ): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Plexo")
        .setContentText(message)
        .setOngoing(message.contains("Downloading") || message == "Preparing download")
        .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            downloaded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), indeterminate || total <= 0)
        .build()

    companion object {
        private const val CHANNEL_ID = "plexo_downloads"
        private const val NOTIFICATION_ID = 41
    }
}