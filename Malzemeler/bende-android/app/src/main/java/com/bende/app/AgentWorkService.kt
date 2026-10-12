package com.bende.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Keeps user-started agent coroutines alive while the activity is not visible. */
object AgentWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var activeJobs = 0

    fun launch(context: Context, block: suspend () -> Unit): Job {
        val appContext = context.applicationContext
        synchronized(lock) {
            activeJobs += 1
            try {
                appContext.startForegroundService(Intent(appContext, AgentWorkService::class.java))
            } catch (error: Exception) {
                activeJobs -= 1
                throw error
            }
        }
        return scope.launch {
            try {
                block()
            } finally {
                val stop = synchronized(lock) {
                    activeJobs = (activeJobs - 1).coerceAtLeast(0)
                    activeJobs == 0
                }
                if (stop) appContext.stopService(Intent(appContext, AgentWorkService::class.java))
            }
        }
    }
}

class AgentWorkService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ajan görevleri", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Bende arka plandayken çalışan kullanıcı başlatımlı görevler"
                setShowBadge(false)
            }
        )
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bende ajanı çalışıyor")
            .setContentText("Görev sürüyor. İlerlemeyi görmek için uygulamaya dön.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_work"
        private const val NOTIFICATION_ID = 4071
    }
}
