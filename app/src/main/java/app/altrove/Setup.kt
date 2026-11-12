package app.altrove

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** What the user has to grant before Altrove can do its job, and how to get there. */
@SuppressLint("InlinedApi") // Only compile-time constants from newer API levels are referenced.
object Setup {

    /** True once Altrove is the app selected under Developer options > Select mock location app. */
    fun isMockLocationApp(context: Context): Boolean =
        mockLocationOpAllowed(context) || canInstallTestProvider(context)

    private fun mockLocationOpAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            // Each of the two calls is the deprecated one on some API level; both just read the mode.
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Second opinion, in case a future Android stops letting apps read their own app-op (Android 17
     * already hides whether Developer options are on): try the real thing with a throwaway
     * provider. Android installs it only for the selected mock location app.
     */
    private fun canInstallTestProvider(context: Context): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            locationManager.addTestProvider(
                PROBE_PROVIDER, false, false, false, false, false, false, false,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE,
            )
            locationManager.allProviders.contains(PROBE_PROVIDER)
        } catch (e: IllegalArgumentException) {
            true // Already there, left over from an earlier probe: so we are allowed.
        } catch (e: RuntimeException) {
            false // Up to Android 11 a SecurityException is how "not selected" is reported.
        } finally {
            try {
                locationManager.removeTestProvider(PROBE_PROVIDER)
            } catch (e: RuntimeException) {
                // Nothing was installed.
            }
        }
    }

    /** Notifications are optional: a session runs without them, just with no visible countdown. */
    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Optional too. Altrove never reads the position, but Google Play services only accepts
     * mock mode from apps holding a location permission (see [LocationMocker]).
     */
    fun locationAllowed(context: Context): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The optional permissions still missing, ready to be requested in one go. */
    fun missingOptionalPermissions(context: Context): List<String> = buildList {
        if (!locationAllowed(context)) addAll(LOCATION_PERMISSIONS)
        if (!notificationsAllowed(context)) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val LOCATION_PERMISSIONS = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Opens Developer options, asking Settings to scroll to and highlight the mock-location row. */
    fun openDeveloperOptions(context: Context) {
        val highlight = Bundle().apply { putString(SETTINGS_ARG_KEY, MOCK_LOCATION_PREFERENCE) }
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(SETTINGS_ARG_KEY, MOCK_LOCATION_PREFERENCE)
            .putExtra(SETTINGS_SHOW_FRAGMENT_ARGS, highlight)
        open(context, intent)
    }

    fun openAboutPhone(context: Context) = open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    private fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            // Some devices don't expose the exact screen: the main Settings page always exists.
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private const val PROBE_PROVIDER = "app.altrove.probe"

    // Extras understood by the AOSP Settings app to highlight one preference on a screen.
    private const val SETTINGS_ARG_KEY = ":settings:fragment_args_key"
    private const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val MOCK_LOCATION_PREFERENCE = "mock_location_app"
}
