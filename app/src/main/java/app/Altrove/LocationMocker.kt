package app.altrove

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * Replaces the device position, system-wide, through Android's official mock-location API.
 *
 * Every provider apps can read from is swapped for a test provider reporting the destination:
 * GPS, network and (Android 12+) the platform fused provider. Google's own fused provider, the
 * one behind Google Maps and most apps, is additionally put in mock mode so it cannot blend the
 * destination with real Wi-Fi or cell fixes.
 *
 * Works only while Altrove is the selected mock location app; otherwise every call is a no-op.
 */
// MissingPermission: the Play services calls are best effort and fail harmlessly without the
// location permission. InlinedApi: only compile-time constants ("fused", ProviderProperties ints).
@SuppressLint("MissingPermission", "InlinedApi")
class LocationMocker(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    private val providers: List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }
    private val mocked = mutableListOf<String>()

    private var playServices: FusedLocationProviderClient? = null
    @Volatile private var playServicesMocking = false
    private var generation = 0

    /** Installs the test providers. Call [push] right after, then about once a second. */
    @Synchronized
    fun start() {
        stop()
        for (provider in providers) {
            try {
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE,
                )
                locationManager.setTestProviderEnabled(provider, true)
                mocked += provider
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot mock the $provider provider", e)
            }
        }
        startPlayServicesMockMode()
    }

    /** Reports [destination] as the current fix on every mocked provider. */
    @Synchronized
    fun push(destination: Coordinates) {
        for (provider in mocked) {
            try {
                locationManager.setTestProviderLocation(provider, fix(provider, destination))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot update the $provider provider", e)
            }
        }
        if (playServicesMocking) {
            try {
                playServices?.setMockLocation(fix(LocationManager.FUSED_PROVIDER, destination))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot update Play services", e)
            }
        }
    }

    /**
     * Hands every provider back to the real hardware. Safe to call when nothing is mocked, which
     * is how leftovers from a process that died mid-session get cleaned up.
     */
    @Synchronized
    fun stop() {
        generation++
        for (provider in providers) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: RuntimeException) {
                // Not mocked (or not ours to remove): nothing to undo.
            }
        }
        mocked.clear()

        playServicesMocking = false
        try {
            playServices?.setMockMode(false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot leave Play services mock mode", e)
        }
        playServices = null
    }

    private fun startPlayServicesMockMode() {
        val expected = generation
        try {
            val client = LocationServices.getFusedLocationProviderClient(appContext)
            playServices = client
            client.setMockMode(true)
                .addOnSuccessListener {
                    synchronized(this) { if (generation == expected) playServicesMocking = true }
                    Log.i(TAG, "Play services mock mode on")
                }
                .addOnFailureListener { Log.i(TAG, "Play services mock mode unavailable: $it") }
        } catch (e: RuntimeException) {
            Log.i(TAG, "Play services mock mode unavailable: $e")
        }
    }

    private fun fix(provider: String, destination: Coordinates): Location =
        Location(provider).apply {
            latitude = destination.latitude
            longitude = destination.longitude
            accuracy = ACCURACY_METERS
            speed = 0f
            speedAccuracyMetersPerSecond = 0.1f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private companion object {
        const val TAG = "Altrove"
        const val ACCURACY_METERS = 4f
    }
}
