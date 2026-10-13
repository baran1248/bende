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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps user-started agent coroutines alive while the activity is not visible. */
object AgentWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var activeJobs = 0
    private var appContext: Context? = null
    private val _activeSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeSessionIds: StateFlow<Set<Long>> = _activeSessionIds

    fun launch(context: Context, sessionId: Long, block: suspend () -> Unit): Job {
        val application = context.applicationContext
        synchronized(lock) {
            activeJobs += 1
            appContext = application
            _activeSessionIds.value = _activeSessionIds.value + sessionId
            savePersistedSessionIds(application, persistedSessionIds(application) + sessionId)
            try {
                application.startForegroundService(Intent(application, AgentWorkService::class.java))
            } catch (error: Exception) {
                activeJobs -= 1
                _activeSessionIds.value = _activeSessionIds.value - sessionId
                savePersistedSessionIds(application, persistedSessionIds(application) - sessionId)
                throw error
            }
        }
        return scope.launch {
            try {
                block()
            } finally {
                val stop = synchronized(lock) {
                    activeJobs = (activeJobs - 1).coerceAtLeast(0)
                    _activeSessionIds.value = _activeSessionIds.value - sessionId
                    savePersistedSessionIds(application, persistedSessionIds(application) - sessionId)
                    activeJobs == 0
                }
                if (stop) application.stopService(Intent(application, AgentWorkService::class.java))
            }
        }
    }

    /** Returns session IDs left by a dead process, preserving jobs still running in this process. */
    fun takeInterruptedSessionIds(context: Context): Set<Long> = synchronized(lock) {
        val application = context.applicationContext
        val interrupted = persistedSessionIds(application) - _activeSessionIds.value
        savePersistedSessionIds(application, persistedSessionIds(application) - interrupted)
        interrupted
    }

    private fun persistedSessionIds(context: Context): Set<Long> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_ACTIVE_SESSIONS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun savePersistedSessionIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ACTIVE_SESSIONS, ids.map(Long::toString).toSet()).apply()
    }

    private const val PREFS = "agent_work"
    private const val KEY_ACTIVE_SESSIONS = "active_session_ids"
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
