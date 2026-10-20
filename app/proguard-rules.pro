# Keep the JS<->native bridge methods (MainActivity.kt's AxomifyNative class) --
# ProGuard/R8 can otherwise strip or rename them since nothing in Kotlin code calls
# them directly (only the web page's JavaScript does, via @JavascriptInterface).
-keepclassmembers class in.axomifymart.rider.MainActivity$AxomifyNativeBridge {
    public *;
}
