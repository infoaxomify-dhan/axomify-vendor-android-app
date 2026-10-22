package `in`.axomifymart.vendor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Axomify Mart Seller" -- same architecture as the sibling Axomify Mart Rider
 * app: a full-screen WebView loading the SAME Vendor Dashboard page every
 * seller already uses today (vendor/views/dashboard.php, Axomify Marketplace
 * plugin). No UI here is duplicated or re-built natively.
 *
 * v1.0.1 ADDED (2026-10-20) -- geolocation permission/bridging code, a direct
 * port of the Rider app's identical handling. NOT for the Vendor Dashboard
 * itself (it still has no live-location feature of its own, confirmed -- no
 * navigator.geolocation call anywhere in the Axomify Marketplace plugin). It
 * is for a completely SEPARATE, sitewide plugin -- "Axomify Location Gate" --
 * which shows a mandatory "share your delivery location" popup on every
 * front-end page (including the Vendor Dashboard) for any visitor with no
 * location saved yet, and calls navigator.geolocation when its own "Use My
 * Location" button is tapped. Without this, that popup's location button was
 * silently blocked inside this app's WebView (Android WebView denies
 * geolocation by default unless the hosting app explicitly grants it here).
 *
 * *** CONFIRM START_URL BELOW is the real, live URL of the page the
 * Vendor Dashboard shortcode is on, before building. Owner's tentative
 * answer: https://axomifymart.in/vendor-dashboard/ -- not yet finally
 * confirmed as of this delivery. ***
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // TODO: confirm/replace with the exact live Vendor Dashboard URL.
        const val START_URL = "https://axomifymart.in/vendor-dashboard/"

        // Points at the NEW role-aware REST route added by Axomify Core
        // v1.3.25 specifically for this app -- a sibling of the Rider app's
        // own /rider-app-version route, built from the exact same
        // Axomify_Core_App_Update_Admin class (now role-aware), so publishing
        // a Vendor APK works through the identical "Axomify Core -> Vendor
        // App Updates" admin page the owner already knows from the Rider
        // app's "App Updates" page.
        const val UPDATE_CHECK_URL = "https://axomifymart.in/wp-json/axomify/v1/vendor-app-version"

        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val TAG = "AxomifyVendorUpdateCheck"

        // v1.0.6 (2026-10-21): owner-reported, same symptom already fixed on the
        // Rider app in its own v1.3.15 -- "white screen after splash, sometimes
        // stuck, never reaches [Vendor] dashboard." Same root cause, direct port
        // of that same fix: the Vendor Dashboard page is also an always-fresh,
        // uncached server render (DONOTCACHEPAGE), so on a slower render this
        // 4000ms safety timeout could fire and drop the splash overlay BEFORE
        // onPageFinished() ever got a chance to, revealing the WebView's own
        // blank-white background underneath. Raised to 7000ms -- gives a slower
        // render more room to finish before the overlay drops on its own, while
        // still hard-capping a genuinely dead connection/site-down case at 7s.
        // (Original safety-net reasoning, still true: caps how long the splash
        // overlay can ever stay up if the page somehow never finishes loading at
        // all, without affecting a normal-speed load either way.)
        private const val SPLASH_SAFETY_TIMEOUT_MS = 7000L
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var splashOverlay: View
    private var splashHidden = false
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        webView = WebView(this)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnRefreshListener {
                webView.reload()
            }
        }

        // Same white-screen fix as the Rider app (v1.3.9) -- keeps the same
        // full-screen splash artwork up as an overlay on top of the WebView
        // until the Vendor Dashboard page actually finishes loading.
        splashOverlay = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.axomify_ink))
            addView(
                ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.splash_fullscreen)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }

        val root = FrameLayout(this).apply {
            addView(swipeRefresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(splashOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        setupWebView()
        requestRuntimePermissions()
        fetchAndCacheFcmToken()
        checkForAppUpdate()

        Handler(Looper.getMainLooper()).postDelayed({ hideSplashOverlay() }, SPLASH_SAFETY_TIMEOUT_MS)

        // v1.0.7 FIXED (2026-10-22) -- owner-reported, same symptom as the
        // Rider app's own v1.3.21 fix (identical root cause, see that file's
        // doc comment for the full diagnosis) -- "Same problem Axomify Mart
        // Seller App mei bhi" (owner's own words). NOT a splash-timing bug:
        // this Activity's manifest already declares
        // android:configChanges="orientation|screenSize|keyboardHidden", so
        // `savedInstanceState` is only ever non-null when Android killed
        // this app's process in the background and restored it later -- and
        // in exactly that case, the old `savedInstanceState == null` guard
        // skipped loadUrl() on the brand-new, blank WebView created just
        // above, leaving it permanently empty once the safety timeout hid
        // the splash. Fixed the same way: always load whenever this fresh
        // WebView has nothing in it yet.
        if (webView.url.isNullOrEmpty()) {
            webView.loadUrl(START_URL)
        }
    }

    private fun hideSplashOverlay() {
        if (splashHidden) return
        splashHidden = true
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(250L)
            .withEndAction { splashOverlay.visibility = View.GONE }
            .start()
    }

    /**
     * Same in-app "a new version is available" check as the Rider app, just
     * pointed at the new Vendor-specific REST route (see UPDATE_CHECK_URL
     * above). Fails completely silently on any error.
     */
    private fun checkForAppUpdate() {
        Thread {
            try {
                val connection = (URL(UPDATE_CHECK_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = connection.responseCode
                if (code != 200) {
                    connection.disconnect()
                    return@Thread
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(body)
                val latestVersionCode = json.optInt("version_code", 0)
                val latestVersionName = json.optString("version_name", "")
                val apkUrl = json.optString("apk_url", "")
                val releaseNotes = json.optString("release_notes", "")

                val currentVersionCode = BuildConfig.VERSION_CODE
                if (latestVersionCode <= currentVersionCode || apkUrl.isBlank()) {
                    return@Thread
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = if (releaseNotes.isNotBlank()) {
                        "Version $latestVersionName available hai.\n\n$releaseNotes"
                    } else {
                        "Version $latestVersionName available hai."
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Naya update available")
                        .setMessage(message)
                        .setPositiveButton("Update") { _, _ ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(this, "Download start nahi ho paya -- phir try kijiye.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Baad mein", null)
                        .setCancelable(true)
                        .show()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Update check skipped: ${e.message}")
            }
        }.start()
    }

    private fun fetchAndCacheFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            getSharedPreferences(AxomifyFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AxomifyFirebaseMessagingService.KEY_PENDING_TOKEN, token)
                .apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage -- the Vendor Dashboard uses this (e.g. 'axmpVendorInDashboard').
            // v1.0.3 ADDED (owner-reported: tapping "Enable" on the Marketplace plugin's
            // "Siren on karein" primer banner worked, but reappeared on every single page
            // refresh instead of staying unlocked -- "Matlab disable ho jata hai?"). Root
            // cause: this WebView's media-autoplay-with-sound permission was left at its
            // default (Android WebView requires an explicit user gesture before any audio
            // plays, stricter than a normal Chrome tab, and that permission is scoped to
            // one page instance -- it can never survive a reload no matter what). Direct
            // port of the EXACT same line + reasoning already proven working in the Rider
            // app's own MainActivity.kt: this does NOT force-unlock audio or bypass the
            // page's own logic -- vendor-dashboard.js's axmpRenderAlertPrimer() still does
            // its own real .play() probe and still shows/hides the banner based on whether
            // that probe actually succeeds. All this does is stop the WebView itself from
            // adding an EXTRA blanket restriction on top of that -- same as a normal
            // browser tab, where the probe's own success/failure is what decides, not a
            // hard-coded "never without a fresh tap this load" rule.
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true) // v1.0.1 -- needed for Axomify Location Gate's popup (see class doc comment above), not the Vendor Dashboard itself.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString AxomifyVendorApp/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // The JS<->native bridge: the page's own JS pulls the FCM token through
        // this (see vendor-dashboard.js's axmpRegisterFcmTokenIfNative(),
        // Axomify Marketplace plugin v1.1.157) instead of this native code
        // making its own authenticated network call.
        webView.addJavascriptInterface(AxomifyNativeBridge(), "AxomifyNativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Direct port of the Rider app's identical v1.3.13 fix, same
                // reasoning -- the Vendor Dashboard's own "Return to AXOMIFY
                // MART" links (vendor/views/dashboard.php, Axomify
                // Marketplace plugin v1.1.157) are marked with a
                // '?axmp_exit_app=1' query parameter (a DIFFERENT name from
                // the Rider app's '?axr_exit_app=1', so the two apps/plugins'
                // markers can never collide). Checked here, before the normal
                // same-domain WebView check below, so every other same-domain
                // link keeps loading inside the app exactly as before.
                if (request.url.getQueryParameter("axmp_exit_app") == "1") {
                    val cleanUri = request.url.buildUpon().clearQuery().apply {
                        for (name in request.url.queryParameterNames) {
                            if (name != "axmp_exit_app") {
                                appendQueryParameter(name, request.url.getQueryParameter(name))
                            }
                        }
                    }.build()
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, cleanUri))
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                // Same exact-domain/subdomain match as the Rider app's own
                // v1.3.10 security fix (built in from day one here, not
                // retrofitted) -- a substring match would let a lookalike
                // domain load inside this trusted WebView, exposing the
                // AxomifyNativeBridge JS interface and the seller's own
                // login session to an attacker-controlled page.
                val host = request.url.host ?: ""
                return if (host == "axomifymart.in" || host.endsWith(".axomifymart.in")) {
                    false // Load inside this WebView.
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        // No app installed that can handle it -- nothing to do.
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                hideSplashOverlay()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                Toast.makeText(this@MainActivity, "Connection is not secure.", Toast.LENGTH_LONG).show()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    hideSplashOverlay()
                }
            }

            // v1.0.7 ADDED (2026-10-22) -- direct port of the Rider app's
            // identical v1.3.21 fix: recovers if the WebView's own renderer
            // process is killed by Android (low-memory devices), instead of
            // leaving a permanently blank surface or crashing the app.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.d(TAG, "WebView render process gone (crashed=${detail.didCrash()}) -- recreating Activity")
                if (view.parent != null) {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                }
                view.destroy()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // v1.0.1 ADDED -- Axomify Location Gate's popup (see class doc
            // comment above) needs this -- a WebView's navigator.geolocation
            // call is blocked until the app itself grants it here (separately
            // from the Android OS runtime permission requested in
            // requestRuntimePermissions() below). Direct port of the Rider
            // app's identical callback.
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val hasFineLocation = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFineLocation) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    requestRuntimePermissions()
                }
            }

            // Lets product-photo / KYC-document <input type="file"> fields on
            // the Vendor Dashboard open the phone's normal camera/gallery
            // picker -- same bridge as the Rider app's identical callback.
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    startActivityForResult(params.createIntent(), 2001)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001) {
            val result = if (resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
        }
    }

    private fun requestRuntimePermissions() {
        // v1.0.1 ADDED -- needed for Axomify Location Gate's popup (see class
        // doc comment above), direct port of the Rider app's identical logic.
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_LOCATION)
        }

        // Android 13+ (API 33) requires this before ANY notification can be
        // shown at all.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    // v1.0.1 ADDED -- direct port of the Rider app's identical callback.
    // Resolves the WebChromeClient's pending geolocation callback (above)
    // once the Android OS runtime-permission dialog is answered.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
    }

    override fun onBackPressed() {
        // Same back-button philosophy as the Rider app (and the Vendor
        // Dashboard's own existing back-button-guard JS, v1.1.145) -- go back
        // within the site's own history first; only actually exit the app
        // once there's nowhere left to go back to.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** Exposed to the web page's JavaScript as `window.AxomifyNativeBridge`. */
    private inner class AxomifyNativeBridge {
        @JavascriptInterface
        fun getFcmToken(): String {
            val prefs = getSharedPreferences(AxomifyFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(AxomifyFirebaseMessagingService.KEY_PENDING_TOKEN, "") ?: ""
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        // v1.0.5 ADDED -- lets the Vendor Dashboard page's own JS (see
        // vendor-dashboard.js's v1.1.160 axmpStopSiren(), Axomify Marketplace
        // plugin) silence a native siren that's mid-playback, from its one
        // existing "Stop Siren" button. Needed because a new order that
        // arrives while this app is already OPEN plays the native siren
        // (AxomifyFirebaseMessagingService's MediaPlayer) with no automatic
        // stop -- SplashActivity.kt's stopActiveSiren() call only runs on a
        // fresh cold-start, which doesn't happen again while already in the
        // foreground. Safe to call even when no siren is currently playing
        // (stopActiveSiren() itself is a no-op in that case).
        @JavascriptInterface
        fun stopSiren() {
            runOnUiThread {
                AxomifyFirebaseMessagingService.stopActiveSiren(this@MainActivity)
            }
        }
    }
}
