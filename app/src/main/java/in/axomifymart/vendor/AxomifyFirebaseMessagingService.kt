package `in`.axomifymart.vendor

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Two separate jobs, deliberately kept narrow:
 *
 * 1. onNewToken() -- Firebase hands this app a device token identifying it for push.
 *    This service does NOT call the website's API directly -- it just saves the
 *    token to SharedPreferences. MainActivity's WebView reads it from there and
 *    hands it to the Vendor Dashboard page's OWN JavaScript via the
 *    AxomifyNativeBridge, which sends it to the server using the site's existing
 *    authenticated AJAX pattern -- see vendor-dashboard.js's
 *    axmpRegisterFcmTokenIfNative() (Axomify Marketplace plugin, v1.1.157).
 *
 * 2. onMessageReceived() -- v1.0.4 ADDED (2026-10-20), "Phase 2" (owner-confirmed:
 *    "Haan, Phase 2 poora bana dijiye", after testing the admin's "Native App
 *    (FCM) Notifications" tool and getting a plain notification with no siren
 *    sound). This is a DIRECT, FAITHFUL PORT of the Rider app's own, now
 *    real-device-confirmed-working onMessageReceived() -- see that file for the
 *    full v1-v8 saga of why this exact design (data-only message, app plays the
 *    siren itself via MediaPlayer, channel stays silent) is the only one that
 *    reliably worked, after three different OS-driven channel-sound variations
 *    all failed identically on a real OEM device. This mirrors how real
 *    delivery/ride-hailing apps (Zomato, Swiggy, Uber Driver) handle this: they
 *    run their OWN code when a push arrives rather than depending on the OS to
 *    honor a channel's stored sound.
 *
 *    ZERO backend changes were needed for this release, confirmed by reading the
 *    actual current source (not assumed): Axomify Core's `Axomify_Core_Fcm_Push::
 *    send_to_token()` already sends a plain DATA-ONLY payload (`title`/`body`/
 *    `url`, no `android.notification.channel_id` field, no role-specific
 *    branching) to EVERY registered native-app token for a user, completely
 *    app-agnostic -- the receiving app's own channel decides everything. And
 *    Axomify Marketplace's `woocommerce/class-order.php::notify_vendors_of_new_order()`
 *    (v1.1.155) ALREADY calls `Axomify_Core_Push::send( $owner_id, 'New Order
 *    Received', ..., vendor_dashboard_url )` for every new order placed at a
 *    vendor's store -- and `Axomify_Core_Push::send()` (`class-push.php`)
 *    ADDITIONALLY calls `Axomify_Core_Fcm_Push::send_to_user()` internally,
 *    alongside its existing Web Push delivery. That whole pipeline was already
 *    fully wired end-to-end; the ONLY missing piece was this Android app not yet
 *    knowing how to play a real siren when a message arrived -- Phase 1 just
 *    showed a plain notification with the channel's old default sound.
 *
 *    As of v1.0.4, the only thing Axomify Marketplace ever pushed to a vendor
 *    was this one "new order" event, so this played the siren for EVERY data
 *    message received, same as Rider's own then-unconditional approach. That
 *    second, non-siren vendor push type this comment anticipated did show up
 *    (Axomify Core's own "Send Announcement" admin tool) -- see this class's
 *    own v1.0.7 doc comment below for the actual `alert_type`-based fix.
 *
 *    The duplicate-message guard is kept from the Rider app's own hard-won lesson
 *    (FCM's "at least once", not exactly-once, delivery guarantee).
 *
 *    v1.0.7 FIXED (2026-10-21): owner-reported -- Axomify Core's own admin "Send
 *    Announcement" broadcast tool was ALSO making this siren play, which makes
 *    no sense for a plain admin message (only a real new-order event should).
 *    This is exactly the "if a second, non-siren vendor push type is ever added
 *    later" case this class's own v1.0.4 doc comment above already flagged --
 *    Axomify Core v1.3.27's `class-fcm-push.php` now adds a plain `alert_type`
 *    field ('order_alert' | 'announcement') to every data message it sends.
 *    `onMessageReceived()` below now only calls `playSirenDirectly()` when that
 *    field is `'order_alert'` (or missing entirely, for backward compatibility
 *    with an un-updated Core plugin -- the siren is the long-standing default
 *    behavior, so an absent field keeps today's exact behavior). An
 *    `'announcement'` message still shows a normal notification (title/body/
 *    vibration, tap-to-open) -- it just never plays the siren or holds the wake
 *    lock for it. Direct port of the same fix in the Rider app's own
 *    AxomifyFirebaseMessagingService.kt (v1.3.16).
 *
 *    v1.0.8 TEMPORARY DIAGNOSTIC (2026-10-21, REMOVED in v1.0.9): owner
 *    re-tested v1.0.7 from the actual "Send Announcement" page and reported
 *    the siren STILL plays. Every code path was re-read end to end looking for
 *    a real bug and found none: Axomify Core's broadcast() -> send_to_user()
 *    -> send_to_token() all correctly pass 'announcement'; this class's own
 *    alert_type gating was correct and byte-for-byte parallel to the Rider
 *    app's confirmed-working v1.3.16; the three sibling plugins' own
 *    `axomify_core_announcement_sent` listeners only write to an in-app
 *    Notification Center, never trigger a second push; the installed app
 *    version was confirmed 1.0.7; caching was confirmed cleared; only ONE
 *    device token was registered; a coincidental real order was ruled out.
 *    v1.0.8 added a temporary debug line to the notification text itself
 *    (`alert_type received: '...'`), shown only when the value was not
 *    'order_alert', so the owner could read the raw received value straight
 *    off their phone. It showed `'announcement'` -- confirming the server
 *    sent, and the app received, the correct value, and that
 *    playSirenDirectly() correctly did NOT run. The Vendor Dashboard's own
 *    Notification Center was then checked directly and showed no
 *    "New Order Received" entry at all -- ruling out a second, real push too.
 *
 *    v1.0.9 FIXED (2026-10-21): with the custom-siren gate itself proven
 *    correct and every other candidate ruled out, the remaining culprit was
 *    found by re-reading the notification-building code one more time: EVERY
 *    notification this class ever built -- real order alert or plain
 *    announcement alike -- was unconditionally marked
 *    `.setPriority(PRIORITY_HIGH)` + `.setCategory(CATEGORY_ALARM)`. Several
 *    Android OEM skins (MIUI/Xiaomi, ColorOS/Oppo, FuntouchOS/Vivo -- common
 *    on real seller phones in India) are documented to play their OWN system
 *    alarm sound for an ALARM-category notification, overriding the
 *    channel's configured silence -- entirely independent of, and bypassing,
 *    this app's own MediaPlayer-based siren gate. That explains "my own
 *    siren code never ran (confirmed by v1.0.8's own debug line), yet the
 *    phone still made an alarm-like sound" exactly. onMessageReceived() below
 *    now only applies CATEGORY_ALARM + PRIORITY_HIGH for a genuine
 *    'order_alert'; an 'announcement' gets the ordinary CATEGORY_MESSAGE +
 *    PRIORITY_DEFAULT treatment any other plain notification gets, which
 *    should not trigger that OEM alarm-sound behavior. The v1.0.8 temporary
 *    debug line has been removed -- it did its job.
 *
 *    v1.0.10 CHANGED (2026-10-21): owner-reported -- even after v1.0.9, a
 *    "Send Announcement" notification still VIBRATED. Category/priority (the
 *    v1.0.9 fix) has no effect on vibration at all -- vibration is entirely a
 *    property of the notification CHANNEL (see AxomifyApplication.kt), and
 *    every notification was still being posted to the one siren channel,
 *    whose `enableVibration(true)` + custom pattern apply regardless of
 *    category/priority. Fixed by posting an `'announcement'` message to a
 *    brand-new, separate `AxomifyApplication.ANNOUNCEMENT_CHANNEL_ID` channel
 *    instead -- no vibration, and no explicit sound override either, so
 *    Android plays the device's own normal default notification tune, same
 *    as any other ordinary app notification. A genuine `'order_alert'` keeps
 *    using the existing siren channel (`AxomifyApplication.CHANNEL_ID`),
 *    completely unchanged.
 */
class AxomifyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_TOKEN, token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        if (isDuplicateMessage(message)) {
            return
        }

        val title = message.data["title"] ?: "Axomify Mart Seller"
        val body = message.data["body"] ?: ""

        // v1.0.9 ADDED -- see this class's own v1.0.9 doc comment above for the full
        // diagnosis. Computed once, right here, and used below for BOTH the
        // notification's category/priority AND the siren decision -- the real
        // v1.0.9 fix is that the category/priority line now depends on this too,
        // not just the siren call.
        val alertType = message.data["alert_type"] ?: "order_alert"

        // Tapping the notification opens the app (via SplashActivity, the real
        // launcher). FLAG_ACTIVITY_NEW_TASK is required when starting an Activity
        // from a Service context like this one; FLAG_ACTIVITY_CLEAR_TOP avoids
        // stacking a second instance if the app is already open in the
        // background. FLAG_IMMUTABLE is required by Android 12+ for any
        // PendingIntent that doesn't need to be modified by the receiving app
        // (which this one doesn't) -- safe on this app's minSdk 24, since the
        // flag itself has existed since API 23.
        val launchIntent = Intent(this, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // v1.0.10 CHANGED -- see this class's own v1.0.10 doc comment above.
        // Vibration is a CHANNEL property, not a category/priority one -- so an
        // announcement now gets posted to a completely separate channel with no
        // vibration and no silence, rather than the order-siren channel.
        val channelId = if (alertType == "order_alert") {
            AxomifyApplication.CHANNEL_ID
        } else {
            AxomifyApplication.ANNOUNCEMENT_CHANNEL_ID
        }

        val notificationId = System.currentTimeMillis().toInt()
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.axomify_gold))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        // v1.0.9 CHANGED -- owner-reported the siren STILL played on a "Send
        // Announcement" test even with v1.0.8 proving (via a temporary on-screen
        // debug line, since removed) that this device correctly received
        // alert_type='announcement' and that playSirenDirectly() below correctly
        // did NOT run for it. With the app's own custom-siren gate proven
        // correct, and every other candidate (stale build, wrong admin tool,
        // caching, a duplicate token, a coincidental real order, a second push of
        // any kind) ruled out one by one (including checking the Vendor
        // Dashboard's own server-side Notification Center, which logs every real
        // order-alert push and showed none), the remaining explanation is that
        // EVERY notification this method built -- order alert or plain
        // announcement alike -- was unconditionally marked
        // `.setPriority(PRIORITY_HIGH)` + `.setCategory(CATEGORY_ALARM)`. Several
        // Android OEM skins (MIUI/Xiaomi, ColorOS/Oppo, FuntouchOS/Vivo -- common
        // on real seller phones) are documented to play their OWN system alarm
        // sound for an ALARM-category notification, overriding the channel's
        // configured silence entirely -- independent of, and bypassing, this
        // app's own MediaPlayer-based siren gate below. That would explain
        // "my own siren code never ran, yet the phone still made an alarm-like
        // sound" exactly. Fix: CATEGORY_ALARM + PRIORITY_HIGH are now applied
        // ONLY for a genuine order alert; an announcement gets the ordinary
        // CATEGORY_MESSAGE + PRIORITY_DEFAULT treatment every other plain
        // notification on the device gets, which should not trigger that OEM
        // alarm-sound behavior.
        //
        // v1.0.10 CHANGED -- the .setDefaults() flag below only matters on
        // Android versions OLDER than 8.0 (API 26), which have no notification
        // channel concept at all -- on API 26+ (this app's target range in
        // practice) the channel assigned above (CHANNEL_ID or
        // ANNOUNCEMENT_CHANNEL_ID) is what actually controls sound/vibration, and
        // this flag is ignored by the OS. It is still set correctly here anyway,
        // for the sake of the handful of API 24-25 devices this app's minSdk
        // still supports: DEFAULT_VIBRATE for a real order alert (silence
        // elsewhere, since playSirenDirectly() plays the siren itself), and
        // DEFAULT_SOUND -- not vibrate -- for an announcement, so even a pre-8.0
        // device plays an ordinary default notification sound with no vibration.
        if (alertType == "order_alert") {
            notificationBuilder
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        } else {
            notificationBuilder
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notificationBuilder.build())
        activeNotificationId = notificationId

        // v1.0.7 ADDED -- see this class's own v1.0.7 doc comment above. Only a
        // real new-order event ('order_alert', or no field at all from an
        // un-updated Core plugin) gets the siren; an admin 'announcement' just
        // shows the plain notification built above, with no siren.
        if (alertType == "order_alert") {
            playSirenDirectly()
        }
    }

    /**
     * Plays the bundled siren file directly via MediaPlayer -- the app's OWN code
     * takes full control of audio playback here, rather than asking the OS to play
     * a sound from the notification channel's configuration (see this file's class
     * doc comment above, and AxomifyApplication.kt's, for why that mechanism
     * proved unreliable on a real device, in Rider's own earlier testing).
     *
     * A partial wake lock is held for the duration of playback -- FCM already
     * wakes the device briefly to deliver a data message, but that window is not
     * guaranteed to stay awake through an async MediaPlayer callback, so this makes
     * sure the clip is not cut off if the device tries to go back to sleep
     * mid-playback. Released as soon as playback completes (or immediately, on any
     * setup error) -- never held longer than needed.
     *
     * `app/src/main/res/raw/axomify_siren.mp3` already carries the owner's own
     * real siren audio (~60s, confirmed byte-for-byte identical to the Rider
     * app's own confirmed-working file -- NOT the earlier synthesized placeholder
     * tone) -- it did not need to be re-bundled for this release.
     */
    private fun playSirenDirectly() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AxomifyVendor:SirenPlaybackWakeLock"
            )
            wakeLock.acquire(65_000L) // safety timeout -- the real siren clip is ~60s

            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val afd = resources.openRawResourceFd(R.raw.axomify_siren)
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setOnCompletionListener { mp ->
                mp.release()
                if (wakeLock.isHeld) wakeLock.release()
                if (activePlayer === mp) activePlayer = null
                if (activeWakeLock === wakeLock) activeWakeLock = null
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (wakeLock.isHeld) wakeLock.release()
                if (activePlayer === mp) activePlayer = null
                if (activeWakeLock === wakeLock) activeWakeLock = null
                true
            }
            player.prepare()
            player.start()

            // Keep a reference so stopActiveSiren() (called the instant the app is
            // opened -- see SplashActivity.kt) can actually stop this early.
            activePlayer = player
            activeWakeLock = wakeLock
        } catch (e: Exception) {
            // Never let a siren-playback failure crash the app or block the
            // notification from showing -- the heads-up notification + vibration
            // (already posted above) still reach the seller either way.
            Log.e(TAG, "Failed to play siren directly", e)
        }
    }

    /**
     * Guards against FCM's documented "at least once" (not exactly-once) delivery
     * guarantee -- an occasional duplicate delivery of the SAME message is normal,
     * expected behavior, not a bug, but it would otherwise fire the siren/alert
     * twice for one order. Compares the incoming message's own `messageId` (FCM
     * assigns a fresh, distinct one to every genuinely separate send) against the
     * last one this service handled; if it's the same id within the last 30
     * seconds, this is a redelivery of a message already handled, so it's skipped.
     * A message with no id at all (shouldn't normally happen) is never treated as
     * a duplicate -- better to risk an extra alert than silently drop a real one.
     */
    private fun isDuplicateMessage(message: RemoteMessage): Boolean {
        val id = message.messageId ?: return false
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastId = prefs.getString(KEY_LAST_MESSAGE_ID, null)
        val lastAt = prefs.getLong(KEY_LAST_MESSAGE_AT, 0L)
        val now = System.currentTimeMillis()
        val isDuplicate = id == lastId && (now - lastAt) < DUPLICATE_WINDOW_MS

        prefs.edit()
            .putString(KEY_LAST_MESSAGE_ID, id)
            .putLong(KEY_LAST_MESSAGE_AT, now)
            .apply()

        return isDuplicate
    }

    companion object {
        const val PREFS_NAME = "axomify_vendor_prefs"
        const val KEY_PENDING_TOKEN = "pending_fcm_token"
        const val KEY_LAST_MESSAGE_ID = "last_fcm_message_id"
        const val KEY_LAST_MESSAGE_AT = "last_fcm_message_at"
        const val DUPLICATE_WINDOW_MS = 30_000L
        private const val TAG = "AxomifyVendorFcmService"

        // Held at the object level (not per-instance) since a new
        // FirebaseMessagingService instance can be created for each message, but
        // there is only ever realistically one order alert in flight at a time
        // for a given vendor. @Volatile because stopActiveSiren() below is called
        // from an Activity (SplashActivity, main thread) while playSirenDirectly()
        // runs in this service -- both need to see the same, up-to-date value.
        @Volatile private var activePlayer: MediaPlayer? = null
        @Volatile private var activeWakeLock: PowerManager.WakeLock? = null
        @Volatile private var activeNotificationId: Int? = null

        /**
         * Called from SplashActivity.onCreate(), i.e. the very first moment the
         * app opens, whether that's from tapping the order notification (now
         * that it's actually clickable -- see onMessageReceived above) or just
         * opening the app normally. Stops and releases any siren still playing
         * from a closed-app alert, and dismisses its notification if it's still
         * showing -- once the vendor has the app open, the Vendor Dashboard
         * page's own existing in-page primer/siren system is the one
         * authoritative alert from that point on, so there is no reason for the
         * native one to keep blaring underneath it.
         */
        fun stopActiveSiren(context: Context) {
            activePlayer?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                } catch (e: IllegalStateException) {
                    // Already stopped/released by its own onCompletion/onError --
                    // nothing left to do.
                }
                player.release()
            }
            activePlayer = null

            activeWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
            activeWakeLock = null

            activeNotificationId?.let { id ->
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
            }
            activeNotificationId = null
        }
    }
}
