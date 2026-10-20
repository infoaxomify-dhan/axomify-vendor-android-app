plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "in.axomifymart.vendor"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.axomifymart.vendor"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }

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
            isMinifyEnabled = false
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("com.google.firebase:firebase-crashlytics-ktx")
}
