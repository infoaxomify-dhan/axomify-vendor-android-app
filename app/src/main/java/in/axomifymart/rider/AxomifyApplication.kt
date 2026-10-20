package `in`.axomifymart.rider

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Creates the "order siren" Android Notification Channel on app startup.
 *
 * THIS is the entire reason this native app exists instead of just continuing to use
 * the website/PWA ("Add to Home Screen") the way riders already do today. A website's
 * push notification can NEVER choose its own sound — no browser has ever implemented
 * that (removed from the Notification API spec in 2018), and that stays true no
 * matter how the site is installed/wrapped. A genuine Android Notification Channel,
 * defined by a real native app, is the only thing that can — and once that channel
 * plays a custom sound, it plays it automatically, for every rider, with zero
 * per-device setup, EVEN WHEN THE APP IS FULLY CLOSED — because Android itself (not
 * this app's code) displays the notification directly from the channel's own saved
 * settings. The channel is created once here, at app startup, so it already exists
 * the very first time Firebase delivers a push.
 *
 * IMPORTANT ANDROID QUIRK, read before ever changing the siren sound file later:
 * once a channel with a given ID has been created on a rider's phone, Android LOCKS
 * its sound forever — re-creating a channel with the same ID and a different sound
 * does nothing on an existing install (a well-documented OS limitation, not a bug
 * here). If the siren sound (app/src/main/res/raw/axomify_siren.mp3) is ever
 * replaced with a different tune after this app is already out in riders' hands, the
 * CHANNEL_ID constant below must also change (e.g. bump "_v2" to "_v3") so every
 * phone creates a fresh channel with the new sound — and AndroidManifest.xml's
 * `default_notification_channel_id` meta-data value must be updated to match, AND
 * Axomify Core's `Axomify_Core_Fcm_Push::RIDER_NOTIFICATION_CHANNEL_ID` constant
 * (includes/class-fcm-push.php) must be updated to match too — that's the value
 * actually sent in the FCM payload's `android.notification.channel_id` field, so
 * it has to stay byte-for-byte identical to this one or a push will route to a
 * channel the device doesn't have (or an old, stale one).
 *
 * v2 note (2026-10-19): bumped from _v1 to _v2 after the first real-device test
 * (Vivo/Oppo/Realme/OnePlus-family phone) showed the notification itself arriving
 * correctly, with correct vibration, and the channel's Sound field correctly
 * showing the custom sound assigned — but the sound never actually played, while
 * every OTHER app's notification sounds played fine on that same phone (ringer
 * Normal, volume up). The file itself was independently verified (decoded back to
 * PCM, confirmed a real, non-silent, correctly-encoded signal) — not the problem.
 * The one other thing a channel's playback behavior depends on is its
 * AudioAttributes usage classification, so this version switches from
 * USAGE_NOTIFICATION_COMMUNICATION_INSTANT (meant for instant-messaging-style
 * notifications) to USAGE_NOTIFICATION_EVENT (Android's own recommended usage for
 * "a notification for a user to attend to an immediate or soon-to-be-attended
 * event" — a closer semantic match for an order-assignment alert, and a more
 * conventional choice some OEM notification-management layers may handle more
 * predictably). That change was NOT confirmed live until the v3 retest below —
 * turned out to be the wrong diagnosis.
 *
 * v3 note (2026-10-19): the v2 retest (first real signed-release install, same
 * Vivo/Oppo/Realme/OnePlus-family phone, battery optimization + autostart +
 * "lock in recents" ALL granted) came back with a NEW symptom — a sound DID
 * play this time, but the wrong one (a generic system "Notification" alert, not
 * the bundled siren), even though the channel's own Sound setting page still
 * correctly showed the custom siren as assigned. Audio playback on this channel
 * clearly CAN work on this OEM (ruling out the v2 theory that the OEM was
 * silently blocking custom channel sounds) — the channel just ended up tied to
 * the wrong actual sound. The most likely cause THEORIZED at the time: the old
 * `soundUri` was built from `R.raw.axomify_siren`, a numeric resource ID baked
 * in AT COMPILE TIME by that specific build — aapt2 is not guaranteed to
 * assign the exact same integer ID to the same resource across two
 * separately-compiled builds. Fixed (it was believed) by switching the sound
 * URI to the NAME-based form (`raw/axomify_siren`) and bumping CHANNEL_ID to
 * _v3 for a completely fresh channel.
 *
 * v4 note (2026-10-19): the v3 retest — SAME phone, confirmed the app WAS
 * manually opened once after the update (so `createOrderSirenChannel()` did
 * run and a genuinely fresh `_v3` channel did get created against the new
 * name-based URI) — came back with the EXACT SAME symptom as v2: wrong/
 * generic default sound, not the siren. Three separate channel-ID bumps (v1,
 * v2, v3) and two different sound-URI schemes (numeric-ID, name-based) have
 * now all failed identically on this device. That rules out every theory
 * about the URI/channel-ID mechanism itself being the problem — something
 * about this OEM's handling of the OS-driven "channel plays its own stored
 * sound when the app is fully closed" pipeline specifically does not reliably
 * honor a channel's custom `setSound()` value, no matter how correctly it is
 * configured.
 *
 * FIX (Plan B, 2026-10-19): stop depending on the channel's own sound-playback
 * mechanism entirely — app plays the siren itself via MediaPlayer, triggered
 * by a data-only FCM message. Initially built but not applied — the owner
 * asked to go back to the original OS-driven design first (v5 revert below),
 * wanting to try the simpler thing before a bigger architecture change.
 *
 * v5 note (2026-10-19, REVERT): briefly went back to the original OS-driven
 * design — `setSound()` with the `_v2`-era AudioAttributes/URI recipe,
 * under a fresh channel ID (`_v5`). Delivered but never actually installed
 * on the test device — before testing it, the owner asked to understand
 * the landscape fully first: what do Zomato/Swiggy/Uber Driver actually do
 * to reliably play a loud alert while closed? Researched and explained:
 * every one of them runs its OWN app code when a push arrives, rather than
 * depending on the OS to honor a channel's stored sound — because this
 * exact OS-driven mechanism is also the one that already failed once before
 * (immediately after the v1.3.0 signed build's own fresh install, before any
 * of the v1/v2/v3 churn even started) and three further channel-ID/sound-URI
 * variations of it also failed identically on this device. **With that
 * understood, the owner explicitly confirmed going with the app-driven
 * approach.**
 *
 * v6 note (2026-10-19, "Tareeka B" — owner-confirmed): back to the
 * app-driven design, architecturally identical to the v4 "Plan B" above —
 * intended to create this channel WITHOUT a custom sound (silent at the OS
 * level). The actual siren audio was meant to be played directly by app
 * code, in `AxomifyFirebaseMessagingService.onMessageReceived()`, using
 * `MediaPlayer` — which only runs because the FCM payload is data-only
 * (Core v1.3.21's `class-fcm-push.php`), so Firebase always hands it to that
 * method in every app state, including fully closed/killed.
 *
 * v7 BUG FOUND AND FIXED (2026-10-19): the v6 real-device test showed the
 * notification banner/text correctly (confirming `onMessageReceived()` DID
 * run), with OEM battery/autostart/recents settings all confirmed still
 * granted — but instead of the custom siren, a generic "notification alarm"
 * tune played, TWICE for one single order. Root cause: **simply never
 * calling `setSound()` on a channel does NOT make it silent** — that was
 * the mistaken assumption in v6. Android then falls back to the SYSTEM'S OWN
 * DEFAULT notification sound for that channel, which is exactly the generic
 * tune heard. (The "twice" part is separate and not an app bug: FCM data
 * messages are only guaranteed "at least once" delivery, not exactly-once,
 * so an occasional duplicate delivery for the same order is expected,
 * documented Firebase behavior — see
 * AxomifyFirebaseMessagingService.kt's v7 doc comment for the duplicate-
 * message guard added to handle it gracefully.) This version calls
 * `setSound(null, null)` EXPLICITLY — the only way to make a channel
 * genuinely silent at the OS level — and bumps CHANNEL_ID to a fresh `_v7`,
 * since the `_v6` channel this phone already created is permanently locked
 * to the (wrong) default-sound configuration it captured on first creation.
 */
class AxomifyApplication : Application() {

    companion object {
        const val CHANNEL_ID = "axomify_order_siren_v7"
    }

    override fun onCreate() {
        super.onCreate()
        createOrderSirenChannel()
    }

    private fun createOrderSirenChannel() {
        // NotificationChannel itself only exists on Android 8.0 (API 26)+ — on
        // anything older, Android always used the app's default notification sound
        // with no per-channel concept at all, so there's nothing to create.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Order Alerts (Siren)",
            // HIGH so it can "heads-up" pop over other apps and reliably ring even
            // when the phone is set to "Do Not Disturb"-adjacent modes a rider on
            // shift would realistically want overridden for a new-order alert.
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Plays a loud siren when a new order/delivery is assigned to you — even if the app is fully closed."
            // v7 fix: explicitly silence the channel — setSound(null, null) is the
            // ONLY way to make a NotificationChannel genuinely silent. Never calling
            // setSound() at all (the v6 mistake) does NOT mean silent — Android then
            // uses its own SYSTEM DEFAULT notification sound instead, which is the
            // generic "notification alarm" tune the owner heard on the v6 test. The
            // actual siren audio is played directly by
            // AxomifyFirebaseMessagingService.onMessageReceived() via MediaPlayer, not
            // by the OS from this channel's own configuration. Vibration/lights/
            // heads-up behavior still come from the channel as before.
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000)
            enableLights(true)
            setBypassDnd(false) // Left off by default — a rider can turn this on themselves per-channel in Android's own Settings if they want it.
        }

        manager.createNotificationChannel(channel)
    }
}
