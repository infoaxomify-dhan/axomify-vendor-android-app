// Axomify Mart Seller -- root Gradle build file.
//
// This project is deliberately a THIN NATIVE WRAPPER, not a rewrite of the Vendor
// Dashboard -- a direct architectural sibling of the existing Axomify Mart Rider
// app. All of the real UI/logic (orders, products, earnings, chat, everything) stays
// exactly where it already lives -- the WordPress site's own
// vendor/views/dashboard.php, loaded here inside a single WebView. Phase 1 scope
// (owner's own words): "Rider jaisa poora shell (splash + exit-fix + FCM-ready)" --
// full shell parity with the Rider app's splash, white-screen fix, exit-to-browser
// fix, and FCM token registration, WITHOUT the Rider app's siren/MediaPlayer logic
// (that's Phase 2 -- new-order alerts for vendors -- explicitly deferred, and not
// yet even sent by the backend). See MainActivity.kt and AxomifyApplication.kt.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // v1.3.0 ADDED -- crash reporting (Firebase Crashlytics). Uses the SAME
    // Firebase project as FCM above (same google-services.json), so there is
    // no separate setup step needed beyond what Firebase setup already did --
    // crashes/ANRs just start appearing under Firebase Console -> Crashlytics
    // for this project automatically, no code in this app needs to call
    // anything for the basic (uncaught exception + ANR) case.
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
}
