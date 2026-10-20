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
 * real devices during the Rider app's own v1.3.7/v1.3.8 rounds). No siren to
 * stop here (unlike Rider's SplashActivity, which calls
 * AxomifyFirebaseMessagingService.stopActiveSiren() first) -- this app has no
 * native siren playback in Phase 1.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DISPLAY_MS = 1200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
