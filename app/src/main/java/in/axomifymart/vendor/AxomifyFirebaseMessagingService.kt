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
 *    Because the only thing Axomify Marketplace currently ever pushes to a
 *    vendor is this one "new order" event (confirmed: no other
 *    Axomify_Core_Push::send()/broadcast() call site targets a vendor user, as
 *    of Marketplace v1.1.159), this plays the siren for EVERY data message
 *    received, same as Rider's own unconditional approach -- if a second,
 *    non-siren vendor push type is ever added later, this method should start
 *    checking a `type` field in the data payload before deciding whether to
 *    play the siren.
 *
 *    The duplicate-message guard is kept from the Rider app's own hard-won lesson
 *    (FCM's "at least once", not exactly-once, delivery guarantee).
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

        val notificationId = System.currentTimeMillis().toInt()
        val notification = NotificationCompat.Builder(this, AxomifyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.axomify_gold))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Vibration/lights/heads-up come from the CHANNEL (see
            // AxomifyApplication.kt) -- the channel is deliberately silent on
            // sound, since playSirenDirectly() below handles that instead.
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
        activeNotificationId = notificationId

        playSirenDirectly()
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
