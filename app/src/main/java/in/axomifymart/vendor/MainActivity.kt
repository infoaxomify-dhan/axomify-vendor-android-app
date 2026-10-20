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
 * Deliberately SIMPLER than the Rider app's MainActivity in one place: no
 * geolocation permission/bridging code. The Vendor Dashboard has no live-
 * location feature (confirmed -- no navigator.geolocation call anywhere in
 * the Axomify Marketplace plugin, unlike the Rider Dashboard's delivery-
 * tracking ping), so that whole permission + WebChromeClient callback is
 * simply not needed here.
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

        private const val REQ_NOTIFICATIONS = 1002
        private const val TAG = "AxomifyVendorUpdateCheck"

        // Same safety-net reasoning as the Rider app's identical constant
        // (see that app's MainActivity.kt v1.3.12 note) -- caps how long the
        // splash overlay can ever stay up if the page somehow never finishes
        // loading (dead internet, site down), without affecting a normal-
        // speed load at all.
        private const val SPLASH_SAFETY_TIMEOUT_MS = 4000L
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var splashOverlay: View
    private var splashHidden = false
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

        if (savedInstanceState == null) {
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
            setGeolocationEnabled(false) // Vendor Dashboard has no location feature -- left explicitly off.
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
        }

        webView.webChromeClient = object : WebChromeClient() {
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
        // Android 13+ (API 33) requires this before ANY notification can be
        // shown at all.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
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
    }
}
