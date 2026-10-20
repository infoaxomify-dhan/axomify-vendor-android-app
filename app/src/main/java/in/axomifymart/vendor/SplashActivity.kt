package `in`.axomifymart.vendor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * The real, full-screen launcher splash -- direct port of the Axomify Mart
 * Rider app's identical SplashActivity.kt (same reasoning, same fix history:
 * a short Handler delay is required before starting MainActivity, otherwise
 * Android can skip rendering this Activity's layout entirely -- confirmed on
 * real devices during the Rider app's own v1.3.7/v1.3.8 rounds).
 *
 * v1.0.4 CHANGED (2026-10-20) -- now also stops any closed-app siren the
 * instant the app opens, a direct port of Rider's own v1.3.11 fix (see
 * AxomifyFirebaseMessagingService.stopActiveSiren()'s own doc comment). Before
 * this, opening the app (from the order notification, now that it's tappable,
 * or just the app icon) did nothing to a native siren already playing from a
 * closed-app alert -- it would have kept blaring for its full ~60 seconds
 * regardless, stacked on top of the Vendor Dashboard page's own in-page siren
 * once the WebView loaded. This is the very first line of this Activity's
 * onCreate() (ahead of setContentView), same as Rider's, so it happens as
 * early as possible.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DISPLAY_MS = 1200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AxomifyFirebaseMessagingService.stopActiveSiren(this)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(0, 0)
                finish()
            }
        }, SPLASH_DISPLAY_MS)
    }
}
