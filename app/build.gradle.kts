plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Reads app/google-services.json at build time to wire up this app to YOUR
    // Firebase project -- see the project's own README.md, step "Firebase project
    // banana" for exactly how to get that file. The placeholder committed here
    // will NOT build correctly on its own -- it must be replaced with the real
    // file downloaded from your own Firebase console.
    id("com.google.gms.google-services")
    // v1.3.0 ADDED -- crash reporting, see the dependency below and the matching
    // plugin declaration in the root build.gradle.kts for the full explanation.
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "in.axomifymart.vendor"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.axomifymart.vendor"
        minSdk = 24 // Android 7.0+ -- same floor as the Rider app, covers the overwhelming majority of real seller phones.
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5" // v1.0.5 (2026-10-20): owner-confirmed go-ahead ("haan chahiye") on the open-app "Enable" banner follow-up offered alongside the Phase 2 delivery. Investigation found this did NOT need the unproven JS<->native audio-bridge architecture originally floated -- the already-existing (since v1.0.0) AxomifyNativeBridge.isNativeApp() JS bridge method was simply never used by any page JS yet. Adds exactly ONE new bridge method, stopSiren(), so the Vendor Dashboard's existing "Stop Siren" button (Axomify Marketplace plugin v1.1.160) can also silence a native siren that's mid-playback when a new order arrives while the app is already open (SplashActivity's own stop-on-launch only covers a cold start). The actual fix for the reappearing "Enable" banner itself is entirely on the Marketplace plugin side (vendor-dashboard.js v1.1.160) -- see that plugin's own changelog; no other Android change was needed for it. v1.0.4 (2026-10-20): "Phase 2" -- real native order-siren, owner-confirmed go-ahead ("Haan, Phase 2 poora bana dijiye") after testing the admin's FCM test-send tool and getting a plain notification with no siren. Direct, faithful port of the Rider app's own now-confirmed-working architecture: AxomifyApplication.kt's channel is now a dedicated, deliberately SILENT "axomify_vendor_order_siren_v1" channel (replacing the old default-sound "axomify_vendor_alerts_v1"), and AxomifyFirebaseMessagingService.onMessageReceived() now plays the already-bundled real siren file (res/raw/axomify_siren.mp3, confirmed identical to Rider's own confirmed-working file, NOT a placeholder) directly via MediaPlayer -- reliable even with the app fully closed/killed, same as Rider's. SplashActivity.kt now stops that native siren the instant the app opens (direct port of Rider's v1.3.11 fix), same as Rider. ZERO backend (Axomify Core/Marketplace) changes were needed -- confirmed by reading the actual current source: Core's FCM sender already sends a generic data-only payload to any registered native-app token, and Marketplace's notify_vendors_of_new_order() (v1.1.155) already calls Axomify_Core_Push::send() for every new order, which already additionally fans out to FCM -- this whole pipeline was already fully wired, the Android app just didn't know how to play a real siren yet. AndroidManifest.xml gained WAKE_LOCK/FOREGROUND_SERVICE permissions (direct port of Rider's) and its default_notification_channel_id meta-data was bumped to match the new channel. v1.0.3 (2026-10-20): owner-reported the Marketplace plugin's "Siren on karein" primer banner's "Enable" tap wasn't staying unlocked -- reappeared on every page refresh. Fixed via mediaPlaybackRequiresUserGesture = false in MainActivity.kt's WebView settings (see that file's own doc comment) -- a direct port of the exact same line already proven working in the Rider app, matching standing practice of porting Rider's siren/media mechanisms into this app when they apply the same way. v1.0.2 (2026-10-20): owner-reported the Vendor Dashboard's own header was rendering under the phone's status bar (time/battery icons overlapping page text) -- fixed via android:windowOptOutEdgeToEdgeEnforcement in themes.xml (see that file's own doc comment -- targetSdk 35 made edge-to-edge mandatory by default on Android 15 devices; this restores normal pre-Android-15 behavior). v1.0.1 (2026-10-20): adds the Rider app's own geolocation permission/bridging code (ACCESS_FINE_LOCATION + onGeolocationPermissionsShowPrompt) -- NOT for the Vendor Dashboard itself, which still has no live-location feature, but for the separate sitewide "Axomify Location Gate" plugin's mandatory delivery-location popup, which calls navigator.geolocation on every front-end page including the Vendor Dashboard and was being silently blocked inside this app's WebView. v1.0.0: first build of "Axomify Mart Seller" -- Phase 1 scope ("Rider jaisa poora shell: splash + exit-fix + FCM-ready"), a direct architectural port of the Axomify Mart Rider app, wrapping the Vendor Dashboard (Axomify Marketplace plugin) instead of the Rider Dashboard. Deliberately excludes the Rider app's siren/MediaPlayer logic -- that's Phase 2 (new-order alerts for vendors), explicitly deferred by the owner; the Marketplace plugin does not send vendors any push yet either.
    }

    // v1.3.0 ADDED -- a stable, owner-owned release signing key. WHY THIS MATTERS:
    // every build before this used Android's own auto-generated DEBUG keystore,
    // which GitHub Actions regenerates FRESH on every run (no persisted
    // ~/.android/debug.keystore across runs) -- so each build's signing
    // certificate was effectively random, meaning a straight "install update
    // over the old app" could fail/behave inconsistently, and a full
    // uninstall-then-reinstall was needed for every single update. A release
    // build signed with ONE real, persistent keystore (generated once, kept
    // safe by the owner, fed into every future build via GitHub Secrets --
    // NEVER committed to the repository itself) fixes that for good: as long
    // as the SAME keystore signs every future build, a new APK can be
    // installed directly over the old one (same as any normal app update),
    // and this same stable certificate is also the prerequisite the owner
    // was told about for ever revisiting Android App Links / deep-linking
    // later (see the project's own notes on that).
    //
    // SAFE BY DESIGN if the secrets aren't set up yet: these four values come
    // from environment variables the GitHub Actions workflow injects from
    // repository Secrets (see .github/workflows/build-apk.yml) -- if they are
    // ever blank (e.g. secrets not configured yet, or someone builds this
    // project locally without them), this signingConfig is simply NOT applied
    // to the release build type below, so `assembleRelease` still builds
    // (just unsigned, not installable yet) and `assembleDebug` is completely
    // unaffected either way.
    val releaseKeystorePath = System.getenv("AXOMIFY_KEYSTORE_PATH")
    val releaseSigningConfigured = !releaseKeystorePath.isNullOrBlank()

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("AXOMIFY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AXOMIFY_KEY_ALIAS")
                keyPassword = System.getenv("AXOMIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Keep this simple to build/debug for v1 -- can be hardened later.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // v1.3.0 ADDED -- AGP 8+ no longer generates the BuildConfig class by
    // default; MainActivity.kt's checkForAppUpdate() reads
    // BuildConfig.VERSION_CODE to compare against the website's latest
    // published version, so this needs to be explicitly turned back on.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1") // Modern WebView feature-detection APIs.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Firebase Cloud Messaging -- the ONE piece a website genuinely cannot do itself.
    // BoM keeps every Firebase artifact's version in sync automatically.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // v1.3.0 ADDED -- crash reporting. Same Firebase project as FCM above (same
    // google-services.json, no separate account/setup needed) -- once this
    // build reaches a device, any uncaught crash or ANR is automatically
    // reported to Firebase Console -> Crashlytics for this project, with zero
    // extra code required for the basic case.
    implementation("com.google.firebase:firebase-crashlytics-ktx")
}
