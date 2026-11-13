package app.altrove

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home-screen widget: one tap starts a 5-minute session, another tap ends it early.
 *
 * It has three looks: "tap to set up" (opens the app), resting (shows the destination) and
 * active (accent colour plus a countdown that the launcher animates by itself).
 */
class AltroveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        _placed.value = true
        manager.updateAppWidget(appWidgetIds, render(context))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        _placed.value = hasInstances(context)
    }

    override fun onDisabled(context: Context) {
        _placed.value = false
    }

    companion object {
        private val _placed = MutableStateFlow(false)

        /** Whether at least one Altrove widget sits on the home screen. */
        val placed: StateFlow<Boolean> = _placed.asStateFlow()

        /** Redraws every placed widget from the current state. Cheap, call it freely. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(component(context))
            _placed.value = ids.isNotEmpty()
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, render(context))
        }

        /** Asks the launcher to place the widget (the user confirms in a system dialog). */
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(component(context), null, null)
        }

        fun canRequestPin(context: Context): Boolean =
            AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true

        private fun hasInstances(context: Context): Boolean =
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(component(context))?.isNotEmpty() == true

        private fun component(context: Context) = ComponentName(context, AltroveWidgetProvider::class.java)

        private fun render(context: Context): RemoteViews {
            val session = Session.active.value
            val destination = Prefs.destination(context)
            return when {
                session != null -> RemoteViews(context.packageName, R.layout.widget_active).apply {
                    // Counts down to the end of the session without any further update from us.
                    setChronometerCountDown(R.id.widget_countdown, true)
                    setChronometer(R.id.widget_countdown, session.endElapsedRealtime, null, true)
                    setTextViewText(R.id.widget_subtitle, session.destination.formatStacked())
                    setContentDescription(
                        android.R.id.background, context.getString(R.string.widget_active_description),
                    )
                    setOnClickPendingIntent(android.R.id.background, toggle(context))
                }

                destination == null || !Setup.isMockLocationApp(context) ->
                    RemoteViews(context.packageName, R.layout.widget_idle).apply {
                        setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_setup))
                        setContentDescription(
                            android.R.id.background, context.getString(R.string.widget_setup_description),
                        )
                        setOnClickPendingIntent(android.R.id.background, openApp(context))
                    }

                else -> RemoteViews(context.packageName, R.layout.widget_idle).apply {
                    setTextViewText(R.id.widget_subtitle, destination.formatStacked())
                    setContentDescription(
                        android.R.id.background,
                        context.getString(R.string.widget_idle_description, destination.format(4)),
                    )
                    setOnClickPendingIntent(android.R.id.background, toggle(context))
                }
            }
        }

        /**
         * Goes straight to the service: a tap on a widget is one of the few moments Android
         * lets an app start a foreground service from the background.
         */
        private fun toggle(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            MockLocationService.intent(context, MockLocationService.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
