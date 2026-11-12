package app.altrove

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** The little that Altrove remembers between launches. */
object Prefs {
    private const val FILE = "altrove"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_SESSION_RUNNING = "session_running"
    private const val KEY_OPTIONAL_PERMISSIONS_HANDLED = "optional_permissions_handled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun latitudeText(context: Context): String = prefs(context).getString(KEY_LATITUDE, null).orEmpty()

    fun longitudeText(context: Context): String = prefs(context).getString(KEY_LONGITUDE, null).orEmpty()

    /** The saved destination, or null until both fields have been saved once. */
    fun destination(context: Context): Coordinates? {
        val latitude = Coordinates.parseLatitude(latitudeText(context)) ?: return null
        val longitude = Coordinates.parseLongitude(longitudeText(context)) ?: return null
        return Coordinates(latitude, longitude)
    }

    fun saveDestination(context: Context, latitude: String, longitude: String) {
        prefs(context).edit {
            putString(KEY_LATITUDE, latitude)
            putString(KEY_LONGITUDE, longitude)
        }
    }

    /**
     * True while a session is running. If the process dies mid-session the flag survives, which is
     * how the next launch knows it has mock providers to clean up (see [AltroveApp]).
     */
    fun sessionRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_SESSION_RUNNING, false)

    fun setSessionRunning(context: Context, running: Boolean) {
        // Synchronous on purpose: the flag must be on disk before the session really starts.
        prefs(context).edit(commit = true) { putBoolean(KEY_SESSION_RUNNING, running) }
    }

    /** True once the user has answered (or dismissed) the optional permissions: never nag twice. */
    fun optionalPermissionsHandled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPTIONAL_PERMISSIONS_HANDLED, false)

    fun setOptionalPermissionsHandled(context: Context) {
        prefs(context).edit { putBoolean(KEY_OPTIONAL_PERMISSIONS_HANDLED, true) }
    }
}
