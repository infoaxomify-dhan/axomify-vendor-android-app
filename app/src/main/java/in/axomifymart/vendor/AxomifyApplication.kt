package `in`.axomifymart.vendor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * "Axomify Mart Seller" -- the native Vendor app, built as a thin WebView wrapper
 * around the SAME Vendor Dashboard every seller already uses on the website
 * (Axomify Marketplace plugin, vendor/views/dashboard.php). This is a direct
 * sibling of the existing "Axomify Mart Rider" native app -- same architecture,
 * same reasoning -- scoped to the owner's own Phase 1 request: *"Rider jaisa
 * poora shell (splash + exit-fix + FCM-ready)"*.
 *
 * PHASE 1 vs PHASE 2, READ THIS BEFORE CHANGING THE CHANNEL BELOW: unlike the
 * Rider app's "Order Alerts (Siren)" channel, this app does NOT yet play any
 * custom loud siren sound, and the Marketplace plugin does not yet send any new-
 * order push to vendors at all (confirmed by grep -- zero calls to
 * Axomify_Core_Push::send()/broadcast() for vendors anywhere in that plugin).
 * That's a deliberately separate, bigger piece of work ("Phase 2") the owner
 * explicitly chose to defer. This channel exists now only so the app has
 * SOMEWHERE correct to show a notification if/when a push is ever sent to it --
 * it uses Android's normal DEFAULT notification sound (never silenced), not a
 * custom siren file. When Phase 2 is actually built, this channel (or a fresh
 * one, same reasoning as the Rider app's channel-ID-bump lesson -- see that
 * app's own AxomifyApplication.kt for the full story of why a channel's sound
 * is locked forever once created on a device) is the place to add a real siren,
 * exactly the way the Rider app's channel was extended.
 */
class AxomifyApplication : Application() {

    companion object {
        const val CHANNEL_ID = "axomify_vendor_alerts_v1"
    }

    override fun onCreate() {
        super.onCreate()
        createAlertsChannel()
    }

    private fun createAlertsChannel() {
        // NotificationChannel itself only exists on Android 8.0 (API 26)+ -- on
        // anything older, Android always used the app's default notification sound
        // with no per-channel concept at all, so there's nothing to create.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Axomify Mart Seller Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Order and account alerts for your Axomify Mart seller account."
            // Deliberately left at Android's own DEFAULT notification sound
            // (never called setSound(null, null) to silence it, unlike the
            // Rider app's siren channel) -- there is no custom tune to play yet,
            // see the class doc comment above for the Phase 1/Phase 2 split.
            enableVibration(true)
            enableLights(true)
        }

        manager.createNotificationChannel(channel)
    }
}
