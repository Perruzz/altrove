package app.altrove

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for "is the position being mocked right now".
 * [MockLocationService] writes it; the widget, the notification and the app screen read it.
 */
object Session {
    /** A session lasts exactly this long, then the real position comes back on its own. */
    const val DURATION_MS = 5 * 60 * 1000L

    /** @property endElapsedRealtime when the session ends, on the [android.os.SystemClock.elapsedRealtime] clock. */
    data class Active(val destination: Coordinates, val endElapsedRealtime: Long)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    val isActive: Boolean get() = _active.value != null

    fun set(session: Active?) {
        _active.value = session
    }

    /** Moves a running session to a new destination without touching its timer. */
    fun retarget(destination: Coordinates) {
        _active.value = _active.value?.copy(destination = destination)
    }
}
