package `in`.axomifymart.rider

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.3.7 ADDED (2026-10-20) -- the real, full-screen launcher splash.
 *
 * Before this version, "splash" meant a plain brand-ink background with a
 * small (160dp) circular logo centered on it (still used, see
 * MainActivity's own Theme.AxomifyRider.Splash / drawable/
 * splash_background.xml) -- the owner asked for a genuine full-screen
 * custom image/design instead (poster-style artwork, not just a logo).
 *
 * WHY A SEPARATE ACTIVITY, not just a bigger drawable on MainActivity's own
 * windowBackground: a windowBackground XML drawable has no true "crop to
 * fill, preserve aspect ratio" mode (only stretch-to-fill, which distorts
 * the image on any phone whose screen ratio doesn't exactly match the
 * source artwork, or center-with-gaps, which leaves blank bars). An
 * ImageView with android:scaleType="centerCrop" is the only reliable way to
 * fill every phone's screen with this artwork, uncropped on the sides where
 * the important content (the rider, the AXOMIFY MART wordmark) sits, with
 * no stretching -- so this needs a real layout, which needs a real Activity.
 *
 * v8 BUG FOUND AND FIXED (2026-10-20): the v1.3.7 build started MainActivity
 * and called finish() in the SAME onCreate() call, with literally zero delay.
 * Confirmed on TWO separate real devices: the full-screen artwork never
 * appeared at all -- the app went straight to MainActivity's own small-logo
 * splash, every time. Root cause: Android never got a chance to actually
 * draw a single frame of this Activity's inflated layout before the task
 * switched away -- finishing an Activity immediately after starting another
 * one, with no yield back to the UI thread first, can skip rendering
 * entirely. A SHORT delay (SPLASH_DISPLAY_MS below) is the simple, standard
 * fix -- long enough to guarantee at least one real frame is composited and
 * seen, short enough to stay a genuine splash and not an annoying wait.
 *
 * v1.3.9 ADDED (2026-10-20): MainActivity now shows this SAME full-screen
 * artwork as an overlay on top of its WebView, keeping it up until the Rider
 * Dashboard page actually finishes loading (see MainActivity.kt's
 * splashOverlay / hideSplashOverlay()) -- fixing a separate "white screen"
 * gap the owner noticed between this Activity finishing and the website's
 * first paint. overridePendingTransition(0, 0) below (and again in
 * MainActivity's onCreate()) makes the hand-off between the two Activities
 * invisible, so the whole thing reads as one continuous splash.
 *
 * v1.3.11 ADDED (2026-10-20): silences any closed-app siren the instant the
 * app opens -- see AxomifyFirebaseMessagingService.stopActiveSiren()'s own
 * doc comment. Before this, opening the app (from the notification or the
 * app icon) did nothing to the native siren already playing from a closed-app
 * alert -- it kept blaring for its full ~60 seconds regardless, stacked on
 * top of the Rider Dashboard page's own in-page siren once the WebView
 * loaded. This is the very first line of this Activity's onCreate() (ahead
 * of setContentView) so it happens as early as possible.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        // v8: must be long enough for Android to actually composite and
        // display at least one frame of this Activity -- a few hundred ms
        // was not reliably enough on the real devices this was tested on,
        // so this errs a bit longer, still short enough to feel intentional
        // rather than a stall.
        private const val SPLASH_DISPLAY_MS = 1200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.3.11 ADDED -- the earliest possible moment to silence a
        // still-playing closed-app siren, before anything else runs.
        AxomifyFirebaseMessagingService.stopActiveSiren(this)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                // v1.3.9 ADDED -- kills the default activity-transition
                // animation (cross-fade/slide) between this Activity and
                // MainActivity, so MainActivity's own splash overlay (same
                // artwork) picks up with zero flicker/gap. MainActivity's
                // own onCreate() also calls this, since the flag has to be
                // set by BOTH the outgoing and incoming Activity to fully
                // suppress it.
                overridePendingTransition(0, 0)
                finish()
            }
        }, SPLASH_DISPLAY_MS)
    }
}
