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
    namespace = "in.axomifymart.rider"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.axomifymart.rider"
        minSdk = 24 // Android 7.0+ -- covers the overwhelming majority of real rider phones.
        targetSdk = 35
        versionCode = 18
        versionName = "1.3.14" // v1.3.14 (2026-10-20): owner-reported, with screenshot: the Rider Dashboard's top area (logo/header) was rendering under the phone's status bar, overlapping the date/time/battery icons. Root cause: targetSdk 35 (Android 15) makes edge-to-edge display mandatory by default, and this app's themes.xml never opted out (unlike the Vendor app, which got this exact fix in its own v1.0.2). Fixed by adding android:windowOptOutEdgeToEdgeEnforcement=true to Theme.AxomifyRider in themes.xml -- restores normal pre-Android-15 behavior (content stays below the status bar), a direct port of the Vendor app's already-confirmed fix. No Kotlin/manifest changes needed. v1.3.13: owner reported the Rider Dashboard's "Return to AXOMIFY MART" link didn't actually leave the app -- it just loaded the homepage inside the same WebView. MainActivity.kt's shouldOverrideUrlLoading() now recognizes a '?axr_exit_app=1' marker (added to that ONE link only, Rider Plugin v44.5.77) and hands it off to the phone's normal external browser via ACTION_VIEW instead of loading it in-app. Every other same-domain link is unaffected.
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
