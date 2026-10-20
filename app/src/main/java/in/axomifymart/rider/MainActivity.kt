package `in`.axomifymart.rider

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
 * Axomify Rider -- the entire native app is, on purpose, just this one screen: a
 * full-screen WebView loading the SAME Rider Dashboard page every rider already uses
 * today (frontend/views/rider-dashboard.php, Axomify Rider Plugin). No UI here is
 * duplicated or re-built natively -- every feature (duty toggle, live orders, chat,
 * cash deposit, KYC, the in-page siren while the app is open) keeps working exactly
 * as it already does, unchanged, because it IS the same website.
 *
 * *** UPDATE START_URL BELOW to the real, live URL of the page this site has the
 * [axomify_rider_dashboard] shortcode on, before building. ***
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // TODO: confirm/replace with the exact live URL (check wp-admin -> Pages for
        // whichever page has the [axomify_rider_dashboard] shortcode on it).
        const val START_URL = "https://axomifymart.in/rider-dashboard/"

        // v1.3.0 ADDED -- in-app "a new version is available" check. Points at a
        // small, public, read-only REST endpoint on the SAME website (Axomify Core
        // plugin, Settings -> App Updates admin page) rather than GitHub, because
        // this project's GitHub repository is PRIVATE -- a rider's phone has no
        // GitHub login and could never read a private repo's release info
        // anonymously. The website is already the one place the owner manages
        // every other app-related setting (FCM, broadcasts), so this keeps that
        // same pattern instead of adding a new system. See Axomify Core's
        // includes/class-app-update-admin.php for the admin side.
        const val UPDATE_CHECK_URL = "https://axomifymart.in/wp-json/axomify/v1/rider-app-version"

        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val TAG = "AxomifyUpdateCheck"

        // v1.3.9 ADDED -- if the Rider Dashboard page somehow never finishes
        // loading (dead internet, site down), the splash overlay below would
        // otherwise cover the screen forever. It is forced down after this
        // long regardless, same as a normal browser tab would eventually show
        // its own error state.
        //
        // v1.3.12 CHANGED (2026-10-20) -- owner-reported: splash was staying
        // up too long on a slow page load. 8000ms was only ever meant as a
        // dead-internet/site-down SAFETY NET, not the normal/expected wait --
        // normally the splash hides the instant onPageFinished() fires,
        // however long (or little) that actually takes. Lowered to 4000ms so
        // the WORST case is capped at 4 seconds instead of 8. Trade-off,
        // explained to and accepted by the owner: if the page genuinely takes
        // longer than 4s to finish loading (slow network/server), the splash
        // will now hide at the 4s mark regardless, and whatever's left of the
        // page may still be loading underneath for a moment -- same brief
        // "incomplete page" gap that existed before v1.3.9, just capped much
        // shorter now (a few seconds, not up to 8). Normal-speed loads are
        // completely unaffected by this change either way.
        private const val SPLASH_SAFETY_TIMEOUT_MS = 4000L
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
        // v1.3.9 ADDED -- kills the default activity-transition animation
        // coming from SplashActivity, so this Activity's own splash overlay
        // (below) picks up EXACTLY where SplashActivity's full-screen artwork
        // left off -- no cross-fade/slide, no flicker, no gap.
        overridePendingTransition(0, 0)

        webView = WebView(this)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnRefreshListener {
                webView.reload()
            }
        }

        // v1.3.9 ADDED -- real fix for the "white screen after splash" gap. A
        // bare WebView's own background is white, and shows that white the
        // instant it's laid out -- well before the Rider Dashboard page has
        // actually fetched/rendered anything, which can be a few seconds on a
        // slow connection. Rather than let that white flash through, this
        // stacks the SAME full-screen splash artwork (splash_fullscreen.jpg,
        // also used by SplashActivity.kt) ON TOP of the WebView, and only
        // fades it away once the page actually finishes loading (see
        // webViewClient.onPageFinished below) -- so what the rider sees is one
        // continuous splash, never a blank page.
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

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        }
    }

    /**
     * v1.3.9 ADDED -- fades the full-screen splash overlay out once the Rider
     * Dashboard page is actually ready (or the safety timeout above fires).
     * Guarded by splashHidden so this only ever runs once -- a pull-to-refresh
     * reload calls onPageFinished again later, and the overlay must stay gone
     * from then on, not reappear.
     */
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
     * v1.3.0 ADDED -- checks the website's own small REST endpoint for a newer
     * app version than this one, and if there is one, shows a simple dialog
     * offering to download it. Runs on a plain background Thread (not a
     * coroutine/WorkManager dependency -- this app deliberately stays light on
     * third-party libraries, same reasoning as every other piece of this
     * project) and fails SILENTLY on any error (no internet, site briefly
     * down, unexpected response) -- a broken/unreachable update check must
     * never block or crash the app someone is just trying to open and start
     * their shift with.
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
                    return@Thread // Already up to date, or server not configured yet -- nothing to show.
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
                // No internet, site unreachable, unexpected response shape, etc. --
                // this check is a convenience, never something worth bothering the
                // rider about. Logged only for a developer looking at logcat.
                Log.d(TAG, "Update check skipped: ${e.message}")
            }
        }.start()
    }

    /**
     * onNewToken() in AxomifyFirebaseMessagingService only fires when the token
     * actually ROTATES, not on every normal app start -- so a fresh install (or one
     * that was reinstalled/cleared) could otherwise have no token cached yet the
     * first time getFcmToken() is called from the page's JS. Asking Firebase
     * directly here guarantees one is already saved before the WebView's page even
     * finishes loading.
     */
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
            domStorageEnabled = true // localStorage -- the site uses this (e.g. 'axr_alerts_primed').
            mediaPlaybackRequiresUserGesture = false // The in-page siren <audio id="sirenAudio"> already manages its own unlock -- let the page's own JS logic decide, same as a normal browser tab would.
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString AxomifyRiderApp/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // The JS<->native bridge: the page's own JS pulls the FCM token through this
        // (see rider-dashboard.php's axrRegisterFcmTokenIfNative()) instead of this
        // native code making its own authenticated network call -- keeps all
        // auth/nonce logic in exactly one place (the website's existing AJAX
        // pattern), nothing duplicated or re-implemented here.
        webView.addJavascriptInterface(AxomifyNativeBridge(), "AxomifyNativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // v1.3.13 ADDED (2026-10-20) -- the Rider Dashboard's own "Return to
                // AXOMIFY MART" link (frontend/views/rider-dashboard.php, Rider
                // Plugin v44.5.77) used to just load the homepage INSIDE this same
                // WebView, same as every other same-domain link -- so tapping it
                // never actually "returned" anywhere; the rider just saw the
                // homepage inside the app shell, with no way back except the
                // dashboard's own in-page navigation. Owner's request: this one
                // link should genuinely exit the app and open the site in the
                // phone's normal external browser. That page marks ONLY this one
                // link with a '?axr_exit_app=1' query parameter -- checked here,
                // before the normal same-domain WebView check below, so every
                // OTHER same-domain link (Profile, Deposit Cash, order pages, etc.)
                // is completely unaffected and keeps loading inside the app exactly
                // as before. The marker itself is stripped before handing off, so
                // the browser opens a clean URL.
                if (request.url.getQueryParameter("axr_exit_app") == "1") {
                    val cleanUri = request.url.buildUpon().clearQuery().apply {
                        for (name in request.url.queryParameterNames) {
                            if (name != "axr_exit_app") {
                                appendQueryParameter(name, request.url.getQueryParameter(name))
                            }
                        }
                    }.build()
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, cleanUri))
                        true // Handed off externally -- don't load inside this WebView.
                    } catch (e: Exception) {
                        // No browser app available to handle it (extremely unlikely) --
                        // fall back to the normal in-app load rather than doing nothing.
                        false
                    }
                }

                // Keep the rider's own site inside the app. Anything else (a tel:
                // link, a wa.me WhatsApp link, an external map link) hands off to
                // whatever app the phone already uses for that -- exactly like a
                // normal browser tab would, via startActivity + ACTION_VIEW.
                val host = request.url.host ?: ""
                // v1.3.10 SECURITY FIX (2026-10-20) -- this used to be
                // host.contains("axomifymart.in"), a plain SUBSTRING match. A
                // lookalike host like "axomifymart.in.attacker-site.com" or
                // "notaxomifymart.in.com" ALSO contains that substring, so it
                // would load inside this trusted WebView too -- exposing the
                // AxomifyNativeBridge JS interface (which hands back the FCM
                // token) and the rider's own login cookies/session to
                // whatever page that attacker-controlled domain served. This
                // is now an exact match on the real domain, or a real
                // subdomain of it -- nothing else can ever qualify.
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
                // Never silently trust a bad cert -- same behavior as a normal
                // browser tab would have.
                handler.cancel()
                Toast.makeText(this@MainActivity, "Connection is not secure.", Toast.LENGTH_LONG).show()
            }

            // v1.3.9 ADDED -- don't leave the rider stuck behind the splash if
            // the main page can't load at all (no internet, site down). Only
            // acts on the MAIN frame's own error, not a minor sub-resource
            // (an image, a font) failing while the page itself is still fine.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    hideSplashOverlay()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Rider Dashboard's live-location ping needs this -- a WebView's
            // navigator.geolocation call is blocked until the app itself grants it
            // here (separately from the Android OS runtime permission requested in
            // requestRuntimePermissions() below).
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

            // Lets KYC-document / deposit-screenshot <input type="file"> fields on
            // the website open the phone's normal camera/gallery picker.
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
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_LOCATION)
        }

        // Android 13+ (API 33) requires this before ANY notification -- including the
        // order siren -- can be shown at all.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

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
        // Mirrors the existing PWA's own back-button handling philosophy (see Axomify
        // Rider Plugin's v44.5.54 changelog) -- go back within the site's own history
        // first; only actually exit the app once there's nowhere left to go back to.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** Exposed to the web page's JavaScript as `window.AxomifyNativeBridge`. */
    private inner class AxomifyNativeBridge {
        /**
         * Called by rider-dashboard.php's own JS once the page is loaded and
         * authenticated. Returns the FCM token saved by
         * AxomifyFirebaseMessagingService.onNewToken() (if any arrived yet), and
         * also proactively asks Firebase for the current token directly -- covers
         * the normal case where a token already existed before this page ever
         * loaded (onNewToken only fires again on an actual refresh/rotation, not
         * every app start).
         */
        @JavascriptInterface
        fun getFcmToken(): String {
            val prefs = getSharedPreferences(AxomifyFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(AxomifyFirebaseMessagingService.KEY_PENDING_TOKEN, "") ?: ""
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true
    }
}
