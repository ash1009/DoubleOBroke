package app.brokeometer.limits

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin for App limits, with no powers over the phone: while it's on, the app can't be
 * uninstalled until this is switched off first, and App limits keeps you off that page.
 */
class LimitAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turn App limits off in Double O Broke first. It takes a 30 minute wait."
}
