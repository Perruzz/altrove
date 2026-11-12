package app.altrove

import android.app.Application

class AltroveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SessionNotification.ensureChannel(this)

        // A session only lives inside this process. If the flag is still set at startup, the
        // previous process died mid-session (crash, force stop, reboot) and may have left its
        // mock providers installed, which would keep the real GPS silent: remove them now.
        if (Prefs.sessionRunning(this)) {
            LocationMocker(this).stop()
            Prefs.setSessionRunning(this, false)
            AltroveWidgetProvider.refresh(this)
        }
    }
}
