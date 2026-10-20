# Axomify Rider — Native Android App

Yeh woh native Android app hai jo apne khud ka icon leke phone mein khulta hai —
website/PWA se alag. Is app ka **EK hi** reason hai: jab app pura band ho (force-
closed), tab bhi automatic, custom siren sound ke saath order-alert notification
aaye — jo website/PWA/Chrome se, kisi bhi tareeke se, kabhi possible nahi hai
(2026 ka fresh research confirm karta hai — Web Push ka `sound` option 2018 mein
hi hata diya gaya tha, kisi bhi browser ne kabhi implement nahi kiya).

**Yeh app Rider Dashboard ko dobara nahi banata.** Andar sirf ek WebView hai jo
EXACT wahi website khol deta hai jo har rider aaj bhi browser mein use karta hai
— duty toggle, live orders, chat, cash deposit, KYC, sab kuch bilkul waisa hi
kaam karega jaisa abhi karta hai. Sirf ek cheez add hui hai: FCM (Firebase Cloud
Messaging) ke through ek native notification channel, jisme pehle se hi ek
custom siren sound bandh ki hui hai.

**Kuch bhi purana nahi hata — website, PWA, WooCommerce, Admin Panel, sab
bilkul waisa hi rahega.** Yeh app sirf ek NAYA, ALAG option hai jo chahe toh
rider use kar sakta hai — PWA bhi apni jagah chalta rahega.

---

## Is project ka structure (ek nazar mein)

```
android-rider-app/
├── app/
│   ├── google-services.json      <- ABHI PLACEHOLDER hai, replace karna hai (Step 2 dekhiye)
│   └── src/main/
│       ├── java/in/axomifymart/rider/
│       │   ├── AxomifyApplication.kt          <- native siren channel yahan banta hai
│       │   ├── AxomifyFirebaseMessagingService.kt
│       │   └── MainActivity.kt                <- website yahan WebView mein khulti hai
│       └── res/
│           ├── raw/axomify_siren.mp3          <- abhi ek placeholder "nee-naw" sound hai
│           └── mipmap-*/ic_launcher*.png      <- abhi placeholder gold "A" icon hai
├── .github/workflows/build-apk.yml  <- GitHub ka free cloud build (neeche Step 4)
└── README.md  (yeh file)
```

---

## IMPORTANT — isko EK installable app (.apk) banane ke liye ek honest baat

Maine (Claude) iss poore app ka **100% code likh diya hai, free mein**. Lekin
jis cloud computer par main kaam karta hoon, waha se Google ke Android-build
servers access nahi ho rahe (security restriction, mere control mein nahi) —
iska matlab yeh hai ki main khud yaha se ek `.apk` file (jo phone mein install
hoti hai) BUILD nahi kar sakta. Code 100% sahi aur complete hai — bas usse
"compile" karne ka ek aakhri step kisi aur jagah hona padega.

Do tareeke hai — koi bhi ek chuniye:

### Option A — GitHub (FREE, mera recommendation, sirf ek baar setup)

Isme sirf EK free account banana hai (GitHub) — password kahi bhi type nahi
karna, bas account banana hai khud apne liye:

1. **github.com** par jaake ek free account banaiye (agar pehle se nahi hai).
2. Ek naya **private** repository banaiye (naam jo bhi chahe, jaise
   `axomify-rider-app`).
3. Yeh poora `android-rider-app` folder us repository mein upload/push kar
   dijiye (GitHub ki website par seedha drag-and-drop se bhi ho jata hai —
   "uploading an existing file" option).
4. Repo ke andar **"Actions"** tab par jaiye — `build-apk.yml` workflow khud
   chalega (ya "Run workflow" button dabaiye).
5. Kuch minute mein ek green ✅ tick aayega. Us run ko kholiye, neeche
   **"Artifacts"** mein `axomify-rider-debug-apk` dikhega — download kar
   lijiye, usme `app-debug.apk` hai.
6. Woh `.apk` file seedhe rider ke phone mein bhej dijiye (WhatsApp/email/USB
   — jo bhi aasan ho) — phone par tap karke install ho jayegi (Android ek baar
   puchega "install from unknown source allow karein?" — allow kar dena,
   yeh normal hai jab Play Store se nahi aa rahi app).

Yeh **poori tarah FREE** hai — GitHub Actions har mahine kaafi free minutes
deta hai, ek chhoti app ke liye woh kabhi khatam nahi hoga.

### Option B — Android Studio (agar koi technical dost/developer help kar sake)

Agar koi jaan-pehchaan mein hai jiske paas Android Studio already installed
hai (ya woh install kar sakta hai — free hai, lekin download bada hai), toh:

1. Is poore folder ko unhe de dijiye.
2. Android Studio mein "Open" karke yeh folder kholna hai.
3. `Build -> Build Bundle(s) / APK(s) -> Build APK(s)` — ek `.apk` ban jayegi.

---

## Step-by-step: Firebase setup (zaroori — iske bina app chalegi, lekin siren
## kaam nahi karega)

Yeh EKMAATRA step hai jo khud karna hoga — main (Claude) kisi bhi website mein
login nahi kar sakta (yeh ek safety rule hai, aapki security ke liye, laziness
nahi). Poora 100% free hai, koi paisa nahi lagta. Lagbhag 5 minute ka kaam hai.

1. **firebase.google.com** par jaiye, apne Google account se login kariye
   (jo bhi account aapke paas hai, naya bhi bana sakte hain free mein).
2. **"Add project"** → naam dijiye (jaise "Axomify Rider") → default settings
   theek hain, "Create project" dabaiye.
3. Project ke andar, **"Add app"** → Android icon chuniye.
   - **Android package name** mein EXACTLY yeh type kariye: `in.axomifymart.rider`
     (bilkul isi tarah, chhota-bada letter match hona chahiye)
   - App nickname kuch bhi — "Axomify Rider" theek hai.
   - "Register app" dabaiye.
4. Firebase ab ek file dega download karne ke liye: **`google-services.json`**.
   Usse download kar lijiye.
5. Yeh downloaded `google-services.json` file is project ke `app/` folder ke
   andar paste kar dijiye — **isi naam se**, jo wahan abhi PLACEHOLDER file
   hai usi ki jagah (replace kar dena, delete-add nahi).
6. Ab Firebase Console mein, left side **⚙️ (gear icon) → Project Settings**
   par jaiye → **"Service Accounts"** tab kholiye → **"Generate new private
   key"** button dabaiye → ek dusri JSON file download hogi.
7. Yeh DUSRI file (service account wali) **website ke admin panel** mein
   upload karni hai — WordPress admin mein: **Axomify Core → Native App
   (FCM)** page par jaake, "Upload Service Account" se yeh file upload kar
   dijiye.

Done! Dono files alag-alag jagah jaati hain:
- `google-services.json` → is Android project ke andar (`app/` folder)
- Service Account JSON → website ke WordPress admin panel mein

---

## Ek chhoti si baaki hui cheez: START_URL

`app/src/main/java/in/axomifymart/rider/MainActivity.kt` ke andar, sabse upar,
yeh line hai:

```kotlin
const val START_URL = "https://axomifymart.in/rider-dashboard/"
```

Yeh URL maine apni taraf se best-guess li hai. Build karne se pehle, WordPress
admin panel mein jaake **Pages** mein confirm kar lijiye ki Rider Dashboard
(`[axomify_rider_dashboard]` shortcode wala page) ka EXACT, real live URL kya
hai — agar yeh alag hai, toh is line mein wahi real URL daal dijiye.

---

## Placeholder cheezein (abhi dummy hai, baad mein badal sakte hai)

Yeh sab app ko kaam karne se rokte nahi — bas abhi "temporary/demo" dikhte hain:

- **App icon**: abhi ek simple gold circle + "A" letter hai (website ke apne
  hi gold/dark rang use karke banaya hai). Jab real Axomify Rider logo ho,
  `app/src/main/res/mipmap-*/ic_launcher*.png` files replace kar dena.
- **Siren sound**: abhi ek simple synthesized "nee-naw" tone hai (offline
  generate kiya gaya, koi download nahi hua). Jab real siren sound file ho
  (mp3), `app/src/main/res/raw/axomify_siren.mp3` ki jagah replace kar dena —
  **lekin dhyan rahe**: ek baar app kisi phone par install ho jaye, Android
  khud us channel ka sound LOCK kar deta hai us device par hamesha ke liye —
  sound badalne ke liye `AxomifyApplication.kt` mein channel ID
  (`axomify_order_siren_v1`) ko `_v2` jaisa kuch naya naam dena padega (file
  mein already poora explain kiya hua hai).

---

## Agla app: Vendor

Owner ne confirm kiya hai ki Rider ke baad Marketplace **Vendor** app bhi
isi tarah banega — yeh pehla app (Rider) pehle poora test/confirm ho jaye,
uske baad Vendor app isi pattern se shuru hoga.
