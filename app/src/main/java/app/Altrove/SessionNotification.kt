package app.altrove

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** The ongoing notification shown during a session: a live countdown and a Stop button. */
object SessionNotification {
    const val ID = 1
    private const val CHANNEL = "session"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel))
            .setDescription(context.getString(R.string.notification_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * @param immediate false for the throwaway notification of a start that is about to be
     *   refused: Android then never gets to show it.
     */
    fun build(
        context: Context,
        destination: Coordinates?,
        endElapsedRealtime: Long,
        immediate: Boolean = true,
    ): Notification {
        val remaining = endElapsedRealtime - SystemClock.elapsedRealtime()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), flags,
        )
        val stop = PendingIntent.getService(
            context, 1, MockLocationService.intent(context, MockLocationService.ACTION_STOP), flags,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(
                destination?.let { context.getString(R.string.notification_text, it.format(4)) },
            )
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), stop)
            // A system-drawn countdown: no need to repost the notification every second.
            .setWhen(System.currentTimeMillis() + remaining)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android 16+: lets the countdown sit in the status bar as a Live Update chip.
            .setRequestPromotedOngoing(true)
            .setForegroundServiceBehavior(
                if (immediate) {
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                } else {
                    NotificationCompat.FOREGROUND_SERVICE_DEFERRED
                },
            )
            .build()
    }
}
