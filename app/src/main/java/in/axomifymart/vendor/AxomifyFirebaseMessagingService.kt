package `in`.axomifymart.vendor

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Two jobs, same pattern as the Axomify Mart Rider app's identical service:
 *
 * 1. onNewToken() -- saves the device's FCM token to SharedPreferences.
 *    MainActivity's WebView reads it from there and hands it to the Vendor
 *    Dashboard page's OWN JavaScript via the AxomifyNativeBridge, which sends
 *    it to the server using the site's existing authenticated AJAX pattern --
 *    see vendor-dashboard.js's axmpRegisterFcmTokenIfNative() (Axomify
 *    Marketplace plugin, v1.1.157) for the other half of this.
 *
 * 2. onMessageReceived() -- deliberately SIMPLE for Phase 1 ("Rider jaisa
 *    poora shell ... FCM-ready", not the siren/alert system itself): builds a
 *    plain notification from whatever title/body this message carries, using
 *    the channel's own DEFAULT sound (AxomifyApplication.kt's channel is never
 *    silenced), and opens the app when tapped. No MediaPlayer, no wake lock, no
 *    custom audio -- that's Phase 2 (new-order siren alerts for vendors),
 *    explicitly deferred by the owner and not yet even sent by the backend
 *    (the Marketplace plugin does not call Axomify_Core_Push::send() for
 *    vendors on a new order yet). This method exists now so the app is simply
 *    READY to display whatever is eventually sent to it, same "FCM-ready"
 *    scope as the token-registration half above.
 *
 * The duplicate-message guard is kept from the Rider app's own hard-won lesson
 * (FCM's "at least once", not exactly-once, delivery guarantee) even though
 * nothing sends this app a message yet -- cheap to include now, saves
 * rediscovering the same real-world bug later once Phase 2 starts sending.
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

        // Accept either a data-only message (title/body under "data", the
        // Rider app's own pattern) or a standard "notification"-type message
        // (title/body under message.notification) -- whichever shape Phase 2
        // ends up sending, this already handles both correctly.
        val title = message.data["title"] ?: message.notification?.title ?: "Axomify Mart Seller"
        val body = message.data["body"] ?: message.notification?.body ?: ""

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
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * Same guard, same reasoning as the Rider app's identical method -- FCM
     * data/notification messages are only guaranteed "at least once" delivery,
     * not exactly-once, so an occasional duplicate redelivery of the SAME
     * message is expected, normal behavior, not a bug.
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
    }
}
