package com.example.gps.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.example.gps.MainActivity

class DriveTrackingService : Service() {
    private lateinit var store: DriveSessionStore
    private var tracker: AndroidGnssTracker? = null
    private var latestState: GnssUiState? = null
    private var lastPersistElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        store = DriveSessionStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndSave()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startRecording(resetSession = intent.getBooleanExtra(EXTRA_RESET, true))
            }

            ACTION_RESUME -> {
                startRecording(resetSession = false)
            }

            else -> {
                if (store.isActive()) {
                    startRecording(resetSession = false)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tracker?.stop()
        tracker = null
        latestState?.let(store::save)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(resetSession: Boolean) {
        if (resetSession) {
            tracker?.stop()
            tracker = null
            latestState = null
            store.clearSession()
        }

        if (tracker != null) return

        store.setActive(true)
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        val initialState = store.load()
        latestState = initialState
        DriveSessionRuntime.publish(initialState)

        tracker = AndroidGnssTracker(
            context = this,
            initialState = initialState,
            onState = { state ->
                latestState = state
                DriveSessionRuntime.publish(state)

                val now = SystemClock.elapsedRealtime()
                if (now - lastPersistElapsed >= PERSIST_INTERVAL_MS) {
                    store.save(state)
                    lastPersistElapsed = now
                }
            },
        ).also { it.start() }
    }

    private fun stopAndSave() {
        tracker?.stop()
        tracker = null

        val finalState = (latestState ?: store.load()).copy(
            sensorLaneReady = false,
            message = "Drive test stopped · summary saved",
        )
        latestState = finalState
        store.save(finalState)
        store.setActive(false)
        DriveSessionRuntime.publish(finalState)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Lane GPS drive recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps GNSS and motion-sensor drive tests recording with the screen off."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Lane GPS drive test recording")
            .setContentText("GNSS + motion data recording. Screen can be off.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LaneGPS:DriveTest",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.example.gps.action.START_DRIVE_TEST"
        const val ACTION_RESUME = "com.example.gps.action.RESUME_DRIVE_TEST"
        const val ACTION_STOP = "com.example.gps.action.STOP_DRIVE_TEST"
        const val EXTRA_RESET = "reset_session"

        private const val CHANNEL_ID = "lane_gps_drive_test"
        private const val NOTIFICATION_ID = 4107
        private const val PERSIST_INTERVAL_MS = 1000L
    }
}
