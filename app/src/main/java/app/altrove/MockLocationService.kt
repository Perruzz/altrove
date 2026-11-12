package app.altrove

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Runs one session: for exactly [Session.DURATION_MS] it reports the saved destination as the
 * device position, once a second, then gives the real providers back and stops itself.
 *
 * It is a foreground service so that the session keeps going while the user is in other apps,
 * and it holds a wake lock so the timer ends on the second even with the screen off.
 */
class MockLocationService : Service() {

    private lateinit var mocker: LocationMocker
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    /** Runs on [worker]: pushes one fix, then reschedules itself until the deadline. */
    private val tick = object : Runnable {
        override fun run() {
            val session = Session.active.value ?: return
            val remaining = session.endElapsedRealtime - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                main.post {
                    // Unless that session was stopped (or replaced) in the meantime.
                    if (Session.active.value?.endElapsedRealtime == session.endElapsedRealtime) end()
                }
                return
            }
            mocker.push(session.destination)
            worker.postDelayed(this, minOf(TICK_MS, remaining))
        }
    }

    override fun onCreate() {
        super.onCreate()
        mocker = LocationMocker(this)
        workerThread = HandlerThread("altrove-mock").also { it.start() }
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val now = SystemClock.elapsedRealtime()
                val bounce = now - lastToggleAt < DEBOUNCE_MS
                lastToggleAt = now
                when {
                    bounce -> ignore()
                    Session.isActive -> end()
                    else -> begin()
                }
            }
            ACTION_START -> if (Session.isActive) ignore() else begin()
            ACTION_STOP -> end()
            ACTION_REFRESH -> if (Session.isActive) refreshNotification() else stopSelf()
            // Restarted by the system after the process died: AltroveApp already cleaned up.
            else -> if (!Session.isActive) stopSelf()
        }
        return START_STICKY
    }

    private fun begin() {
        val destination = Prefs.destination(this)
        val end = SystemClock.elapsedRealtime() + Session.DURATION_MS
        val ready = destination != null && Setup.isMockLocationApp(this)

        // Started with startForegroundService(): going foreground comes first, no matter what.
        val notification = SessionNotification.build(this, destination, end, immediate = ready)
        if (!enterForeground(notification)) {
            stopSelf()
            return
        }
        if (destination == null || !ready) {
            refuse(if (destination == null) R.string.toast_no_destination else R.string.toast_not_mock_app)
            return
        }

        acquireWakeLock()
        Prefs.setSessionRunning(this, true)
        Session.set(Session.Active(destination, end))
        AltroveWidgetProvider.refresh(this)
        worker.post {
            mocker.start()
            tick.run()
        }
        Log.i(TAG, "Session started")
    }

    private fun end() {
        Session.set(null)
        worker.removeCallbacksAndMessages(null)
        mocker.stop()
        releaseWakeLock()
        Prefs.setSessionRunning(this, false)
        AltroveWidgetProvider.refresh(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Session ended")
    }

    /** The widget was tapped while Altrove can't work yet: say why and bring the app up. */
    private fun refuse(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.i(TAG, "Cannot open the app from the background", e)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A start request that changes nothing still has to honour the foreground-service contract. */
    private fun ignore() {
        if (Session.isActive) return
        val now = SystemClock.elapsedRealtime()
        if (enterForeground(SessionNotification.build(this, null, now, immediate = false))) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun refreshNotification() {
        val session = Session.active.value ?: return
        val notification = SessionNotification.build(this, session.destination, session.endElapsedRealtime)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(SessionNotification.ID, notification)
            } catch (e: SecurityException) {
                Log.i(TAG, "Notifications are off", e)
            }
        }
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, SessionNotification.ID, notification, type)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot run in the foreground", e)
        false
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "altrove:session").apply {
            setReferenceCounted(false)
            acquire(Session.DURATION_MS + WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (Session.isActive) {
            // Destroyed without going through end(): leave no mock provider behind.
            Session.set(null)
            mocker.stop()
            Prefs.setSessionRunning(this, false)
            AltroveWidgetProvider.refresh(this)
        }
        releaseWakeLock()
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        /** From the widget: start a session, or stop the running one. */
        const val ACTION_TOGGLE = "app.altrove.action.TOGGLE"
        const val ACTION_START = "app.altrove.action.START"
        const val ACTION_STOP = "app.altrove.action.STOP"

        /** The destination of the running session changed: update the notification text. */
        const val ACTION_REFRESH = "app.altrove.action.REFRESH"

        private const val TAG = "Altrove"
        private const val TICK_MS = 1000L
        private const val DEBOUNCE_MS = 600L
        private const val WAKE_LOCK_MARGIN_MS = 15_000L

        /** Process-wide, so a double tap is caught even when the first tap stopped the service. */
        private var lastToggleAt = 0L

        fun intent(context: Context, action: String): Intent =
            Intent(context, MockLocationService::class.java).setAction(action)
    }
}
