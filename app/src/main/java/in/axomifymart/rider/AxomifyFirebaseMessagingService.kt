package `in`.axomifymart.rider

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
 *    This service does NOT call the website's API directly (no native HTTP call, no
 *    duplicated auth logic) -- it just saves the token to SharedPreferences.
 *    MainActivity's WebView reads it from there once the Rider Dashboard page has
 *    finished loading and is already logged in, and hands it to that page's OWN
 *    JavaScript via the AxomifyNativeBridge -- which then sends it to the server
 *    using the exact same authenticated AJAX pattern (nonce + fetch()) every other
 *    action on that page already uses. See MainActivity.kt and
 *    frontend/views/rider-dashboard.php's own new axrRegisterFcmTokenIfNative()
 *    function (Axomify Rider Plugin) for the other half of this.
 *
 * 2. onMessageReceived() -- v6 "Tareeka B" rewrite (2026-10-19, owner-confirmed
 *    after a full discussion of the alternatives -- see AxomifyApplication.kt's
 *    v6/v7 doc comments). Previously, the server sent a "notification"-type FCM
 *    message, which the OS auto-displays directly from AxomifyApplication's
 *    channel (sound and all) WITHOUT ever running this method, whenever the app
 *    was backgrounded/closed -- this method only ran while foregrounded. That
 *    OS-driven pipeline is exactly what kept failing on a real test device across
 *    FOUR separate channel-ID/sound-URI/AudioAttributes variations (v1->v2, v2
 *    retest, v2->v3, and the v5 recipe that failed once before any of this churn
 *    even started) -- the channel never reliably played the bundled siren, only
 *    ever vibration or a generic OEM default sound, no matter how correctly it
 *    was configured. This mirrors how real delivery/ride-hailing apps (Zomato,
 *    Swiggy, Uber Driver) handle this: they run their OWN code when a push
 *    arrives rather than depending on the OS to honor a channel's stored sound.
 *
 *    Core v1.3.21's `class-fcm-push.php` now sends a DATA-ONLY message instead (no
 *    top-level "notification" key) -- Firebase always hands a data message to this
 *    method, in EVERY app state (foreground, background, and fully closed/killed),
 *    unlike a notification-type message. That means this method is now the ONLY
 *    place a push notification gets built and shown, in every app state -- and
 *    critically, it plays the siren sound ITSELF via MediaPlayer, completely
 *    bypassing the OS's own channel-sound playback (which is what kept failing).
 *    AxomifyApplication's channel is now created WITHOUT a sound (silent at the OS
 *    level, as of the v7 fix) specifically so there is no ambiguity about which
 *    mechanism is playing the siren -- this method is the only one that ever does.
 *
 *    v7 ADDED (2026-10-19): a duplicate-message guard. The v6 real-device test
 *    showed the same order's alert firing TWICE -- not an app bug: FCM data
 *    messages only guarantee "at least once" delivery, not exactly-once, so an
 *    occasional duplicate delivery for the same message is expected, documented
 *    Firebase behavior. `isDuplicateMessage()` below tracks the last-seen FCM
 *    `messageId` and skips building a second notification/siren if the SAME id
 *    arrives again within 30 seconds -- a genuinely new order always has its own
 *    distinct `messageId`, so this never suppresses a real, separate alert.
 *
 *    v8 ROOT CAUSE FOUND AND FIXED (2026-10-20): the v7 real-device test (channel
 *    genuinely silenced this time) STILL did not play the owner's real siren -- a
 *    DIFFERENT tune played instead, even with the app fully closed. The owner's
 *    own diagnostic (manually setting a custom sound on the channel via Android's
 *    own Settings UI, which DID play the correct siren) proved that MediaPlayer
 *    playback itself, and closed-app delivery, were BOTH already working correctly
 *    -- the only thing wrong was WHICH file was being played. `res/raw/
 *    axomify_siren.mp3` had been a synthesized placeholder "nee-naw" tone ever
 *    since this project's very first delivery (this cloud build environment had
 *    no way to obtain the owner's real audio file on its own) and was never
 *    actually replaced with the owner's real siren -- every "wrong sound" symptom
 *    logged across this entire project likely traces back to this one file. Fixed
 *    by bundling the owner's actual supplied siren file (now ~60 seconds) in place
 *    of the placeholder. See `playSirenDirectly()`'s updated wake-lock comment
 *    below for the one follow-on change this required.
 *
 *    v1.3.11 FIXED (2026-10-20): two more real-device bugs, now that the
 *    siren itself plays the correct audio. (1) The notification built below
 *    had no `setContentIntent()` at all, so tapping it did nothing -- it
 *    never opened the app. (2) `playSirenDirectly()`'s MediaPlayer was pure
 *    fire-and-forget, with no reference kept anywhere else -- so once the
 *    rider opened the app (whether by tapping this now-fixed notification,
 *    or just tapping the app icon), there was no way to stop it, and it kept
 *    blaring for its full ~60 seconds, audibly stacked on top of the Rider
 *    Dashboard page's own existing in-page siren once the WebView loaded.
 *    See `stopActiveSiren()` below and SplashActivity.kt's own v1.3.11 note
 *    for the fix to both.
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

        val title = message.data["title"] ?: "Axomify Mart"
        val body = message.data["body"] ?: ""

        // v1.3.11 ADDED -- tapping the notification now actually opens the app
        // (via SplashActivity, the real launcher). FLAG_ACTIVITY_NEW_TASK is
        // required when starting an Activity from a Service context like this
        // one; FLAG_ACTIVITY_CLEAR_TOP avoids stacking a second instance if the
        // app is already open in the background. FLAG_IMMUTABLE is required by
        // Android 12+ for any PendingIntent that doesn't need to be modified by
        // the receiving app (which this one doesn't) -- safe on this app's
        // minSdk 24, since the flag itself has existed since API 23.
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
            // AxomifyApplication.kt) -- the channel is deliberately silent on sound,
            // since playSirenDirectly() below handles that instead.
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
     * doc comment above for why that mechanism proved unreliable on a real device).
     *
     * A partial wake lock is held for the duration of playback -- FCM already
     * wakes the device briefly to deliver a data message, but that window is not
     * guaranteed to stay awake through an async MediaPlayer callback, so this makes
     * sure the clip is not cut off if the device tries to go back to sleep
     * mid-playback. Released as soon as playback completes (or immediately, on any
     * setup error) -- never held longer than needed.
     *
     * v8 (2026-10-20): the real bundled siren file is ~60 seconds (the earlier
     * placeholder tone was only ~3s) -- the safety timeout below is bumped to
     * match, with a few seconds of margin, so a full play-through is never cut
     * off mid-siren.
     */
    private fun playSirenDirectly() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AxomifyRider:SirenPlaybackWakeLock"
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

            // v1.3.11 ADDED -- keep a reference so stopActiveSiren() (called the
            // instant the app is opened -- see SplashActivity.kt) can actually
            // stop this early. Before this, these were plain local variables with
            // no way to reach them from anywhere else, so the siren always played
            // out its full ~60 seconds regardless of what happened afterwards.
            activePlayer = player
            activeWakeLock = wakeLock
        } catch (e: Exception) {
            // Never let a siren-playback failure crash the app or block the
            // notification from showing -- the heads-up notification + vibration
            // (already posted above) still reach the rider either way.
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
        const val PREFS_NAME = "axomify_rider_prefs"
        const val KEY_PENDING_TOKEN = "pending_fcm_token"
        const val KEY_LAST_MESSAGE_ID = "last_fcm_message_id"
        const val KEY_LAST_MESSAGE_AT = "last_fcm_message_at"
        const val DUPLICATE_WINDOW_MS = 30_000L
        private const val TAG = "AxomifyFcmService"

        // v1.3.11 ADDED -- held at the object level (not per-instance) since a
        // new FirebaseMessagingService instance can be created for each message,
        // but there is only ever realistically one order alert in flight at a
        // time for a given rider. @Volatile because stopActiveSiren() below is
        // called from an Activity (SplashActivity, main thread) while
        // playSirenDirectly() runs in this service -- both need to see the same,
        // up-to-date value.
        @Volatile private var activePlayer: MediaPlayer? = null
        @Volatile private var activeWakeLock: PowerManager.WakeLock? = null
        @Volatile private var activeNotificationId: Int? = null

        /**
         * v1.3.11 ADDED -- called from SplashActivity.onCreate(), i.e. the very
         * first moment the app opens, whether that's from tapping the order
         * notification (now that it's actually clickable -- see onMessageReceived
         * above) or just opening the app normally. Stops and releases any siren
         * still playing from a closed-app alert, and dismisses its notification
         * if it's still showing -- once the rider has the app open, the Rider
         * Dashboard page's own existing in-page siren (tied to the real
         * order-accept action) is the one authoritative alert from that point on,
         * so there is no reason for the native one to keep blaring underneath it.
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
