// Axomify Rider -- root Gradle build file.
//
// This project is deliberately a THIN NATIVE WRAPPER, not a rewrite of the Rider
// Dashboard. All of the real UI/logic (duty toggle, live orders, chat, cash deposit,
// KYC, everything) stays exactly where it already lives -- the WordPress site's own
// frontend/views/rider-dashboard.php, loaded here inside a single WebView. The ONLY
// reason this native app exists at all is the one thing a website/PWA can never do:
// play a CUSTOM, LOUD siren sound automatically when the app is fully closed (not
// just backgrounded). See MainActivity.kt and AxomifyApplication.kt for how that
// piece works. Everything else is standard "wrap my website" boilerplate.
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
