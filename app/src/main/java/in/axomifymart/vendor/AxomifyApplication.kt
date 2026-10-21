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
 * v1.0.4 ADDED (2026-10-20) -- "Phase 2" (owner-confirmed go-ahead: "Haan,
 * Phase 2 poora bana dijiye", after testing the admin's "Native App (FCM)
 * Notifications" tool and getting a plain notification with no siren sound).
 * This REPLACES the Phase 1 generic "Axomify Mart Seller Alerts" channel
 * (`axomify_vendor_alerts_v1`, default Android sound) with a dedicated,
 * DELIBERATELY SILENT order-siren channel -- a direct, faithful port of the
 * Rider app's own `AxomifyApplication.kt`, which only reached a reliable,
 * confirmed-working design after a long real-device debugging saga (read that
 * file's own v1-v8 doc comments for the full history before ever touching
 * this one). The short version: letting Android itself play a channel's
 * stored custom sound turned out to be UNRELIABLE across real OEM devices --
 * three separate channel-ID/sound-URI/AudioAttributes variations all failed
 * identically on Rider's own test device (correct notification + vibration,
 * but wrong/no sound). The only approach that actually worked: make the
 * channel SILENT at the OS level and have the app's OWN code play the siren
 * directly via MediaPlayer, in AxomifyFirebaseMessagingService.onMessageReceived()
 * -- see that file for the actual playback logic.
 *
 * IMPORTANT ANDROID QUIRK, read before ever changing the siren sound/vibration
 * later: once a channel with a given ID has been created on a seller's phone,
 * Android LOCKS its configuration forever -- re-creating a channel with the
 * SAME ID and different settings does nothing on an existing install (this is
 * exactly why this channel gets a brand-new id, `axomify_vendor_order_siren_v1`,
 * instead of reusing/modifying the old `axomify_vendor_alerts_v1` -- that old
 * channel already exists with the DEFAULT Android sound on any phone that
 * installed v1.0.0-v1.0.3, and nothing can change that after the fact). If
 * this channel's own config is ever changed again, bump `_v1` to `_v2` here
 * AND in AndroidManifest.xml's `default_notification_channel_id` meta-data AND
 * Axomify Core's `Axomify_Core_Fcm_Push::RIDER_NOTIFICATION_CHANNEL_ID`-style
 * constant if/when a Vendor-specific one is ever added there (not needed as of
 * this version -- see this app's own AxomifyFirebaseMessagingService.kt v1.0.4
 * doc comment for why the backend needed ZERO changes for this release).
 *
 * v1.0.10 ADDED (2026-10-21) -- owner-reported: even after v1.0.9 stopped the
 * siren/alarm sound on a "Send Announcement" push, the notification still
 * VIBRATED, and the owner wants an announcement to behave exactly like any
 * other ordinary app notification instead -- no vibration, and the phone's
 * own normal default notification tune playing, not silence and not the
 * siren. The CHANNEL above was never the problem for sound (v1.0.9 already
 * fixed that at the category/priority level), but its VIBRATION setting
 * (`enableVibration(true)` + a custom pattern, right below) applies to EVERY
 * notification posted to this channel, order alert or announcement alike --
 * category/priority has no effect on vibration at all, which is exactly why
 * v1.0.9 alone did not stop it. Since a channel's configuration is locked
 * forever once created on a seller's phone (see the IMPORTANT ANDROID QUIRK
 * note above), the only correct fix is a brand-new, SEPARATE channel
 * dedicated to announcements -- see `ANNOUNCEMENT_CHANNEL_ID` and
 * `createAnnouncementChannel()` below. `AxomifyFirebaseMessagingService.kt`'s
 * own v1.0.10 doc comment covers how `onMessageReceived()` now picks between
 * the two channels.
 */
class AxomifyApplication : Application() {

    companion object {
        const val CHANNEL_ID = "axomify_vendor_order_siren_v1"

        // v1.0.10 ADDED -- see this class's own v1.0.10 doc comment above.
        const val ANNOUNCEMENT_CHANNEL_ID = "axomify_vendor_announcement_v1"
    }

    override fun onCreate() {
        super.onCreate()
        createOrderSirenChannel()
        createAnnouncementChannel()
    }

    private fun createOrderSirenChannel() {
        // NotificationChannel itself only exists on Android 8.0 (API 26)+ -- on
        // anything older, Android always used the app's default notification sound
        // with no per-channel concept at all, so there's nothing to create.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Order Alerts (Siren)",
            // HIGH so it can "heads-up" pop over other apps and reliably ring even
            // when the phone is set to "Do Not Disturb"-adjacent modes a seller on
            // shift would realistically want overridden for a new-order alert.
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Plays a loud siren when a new order arrives at your store -- even if the app is fully closed."
            // Deliberately, explicitly silent -- setSound(null, null) is the ONLY
            // way to make a NotificationChannel genuinely silent. Simply never
            // calling setSound() at all does NOT mean silent -- Android then falls
            // back to its own SYSTEM DEFAULT notification sound instead (this is
            // the exact mistake Rider's own v6 channel made; see its doc comment).
            // The actual siren audio is played directly by
            // AxomifyFirebaseMessagingService.onMessageReceived() via MediaPlayer,
            // not by the OS from this channel's own configuration.
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000)
            enableLights(true)
            setBypassDnd(false) // Left off by default -- a seller can turn this on themselves per-channel in Android's own Settings if they want it.
        }

        manager.createNotificationChannel(channel)
    }

    /**
     * v1.0.10 ADDED -- see this class's own v1.0.10 doc comment above. A plain
     * admin "Send Announcement" push should feel like ANY other ordinary app
     * notification: no siren, no vibration, just the phone's own normal
     * default notification tune. DEFAULT importance (not HIGH) -- it shows in
     * the shade and plays a sound, but does not "heads-up" pop over whatever
     * the seller is doing, matching a plain, non-urgent announcement rather
     * than a time-critical order alert.
     */
    private fun createAnnouncementChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            ANNOUNCEMENT_CHANNEL_ID,
            "Announcements",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Plain notifications for admin announcements -- no siren, no vibration, just your phone's normal default notification sound, same as any other app."
            // Deliberately NOT calling setSound() here. Leaving it unset is what
            // makes Android use the DEVICE'S OWN system default notification sound
            // (Settings -> Sound -> Default notification sound) -- the exact
            // opposite of the siren channel above, which explicitly calls
            // setSound(null, null) to go silent on purpose. This channel wants
            // neither silence nor a custom siren, just the ordinary default tune.
            enableVibration(false)
            enableLights(false)
            setBypassDnd(false)
        }

        manager.createNotificationChannel(channel)
    }
}
