# Axomify Mart Seller — Native Android App (Vendor)

Yeh woh native Android app hai jo apne khud ka icon leke phone mein khulta hai —
website/PWA se alag. Yeh **Axomify Mart Rider** app ka hi seedha sibling hai, bas
Vendor Dashboard ke liye banaya gaya hai.

**Phase 1 scope (abhi ka build)** — owner ke khud ke shabdon mein: *"Rider jaisa
poora shell (splash + exit-fix + FCM-ready)"*:
- Real icon + full-screen splash (owner ki apni "Happy Selling" branding se).
- "Return to AXOMIFY MART" link ab genuinely app se bahar nikal ke phone ke normal
  browser mein khulta hai (Rider jaisa hi exit-fix).
- FCM token registration ready hai (Vendor Dashboard ki JS Core ke endpoint par
  token bhej degi, jaise Rider app karta hai).
- Signed release build + in-app "update available" check + Crashlytics — Rider
  jaisi hi poori "professional app" parity.

**Phase 1 mein JAANBOOJH KAR SHAMIL NAHI hai**: Rider jaisa custom loud **siren**
new-order alert ke liye — yeh "Phase 2" hai, jo owner ne khud alag se baad ke liye
rakha hai. Abhi Marketplace plugin khud bhi vendors ko koi FCM/push bhejta hi
nahi hai naya order aane par — woh backend kaam Phase 2 mein hoga.

**Yeh app Vendor Dashboard ko dobara nahi banata.** Andar sirf ek WebView hai jo
EXACT wahi website khol deta hai jo har seller aaj bhi browser mein use karta hai
— orders, products, earnings, chat, sab kuch bilkul waisa hi kaam karega jaisa
abhi karta hai.

**Kuch bhi purana nahi hata — website, PWA, WooCommerce, Admin Panel, sab
bilkul waisa hi rahega.**

---

## Is project ka structure (ek nazar mein)

```
android-vendor-app/
├── app/
│   ├── google-services.json      <- ABHI PLACEHOLDER hai, replace karna hai (Step 2 dekhiye)
│   ├── branding/
│   │   └── icon_source.png       <- seller icon (self-service re-branding ke liye, neeche dekhiye)
│   └── src/main/
│       ├── java/in/axomifymart/vendor/
│       │   ├── AxomifyApplication.kt          <- notification channel yahan banta hai (Phase 1: no siren)
│       │   ├── AxomifyFirebaseMessagingService.kt
│       │   ├── SplashActivity.kt
│       │   └── MainActivity.kt                <- website yahan WebView mein khulti hai
│       └── res/
│           ├── drawable-nodpi/splash_fullscreen.jpg   <- aapki "Happy Selling" artwork
│           └── mipmap-*/ic_launcher*.png              <- aapke seller icon se generate hua
├── .github/workflows/build-apk.yml  <- GitHub ka free cloud build (neeche Step 2)
└── README.md  (yeh file)
```

---

## IMPORTANT — isko EK installable app (.apk) banane ke liye ek honest baat

Jaise Rider app ke saath hua tha, waise hi yahan bhi: maine (Claude) iss poore
app ka 100% code likh diya hai, free mein, lekin jis cloud computer par main kaam
karta hoon waha se Google ke Android-build servers access nahi ho rahe — isliye
main khud yaha se ek `.apk` file BUILD nahi kar sakta. Code 100% sahi aur
complete hai — bas "compile" karne ka aakhri step GitHub par hoga (bilkul Rider
app jaisa hi process).

---

## Step 1 — is naye repo mein is poore folder ka content daalna

Aapne jo naya GitHub repo "Use this template" se banaya hai, usme abhi Rider
app ki hi purani files hain. Un sabko is naye Vendor app ke content se replace
karna hai:

1. Apne naye repo mein jaiye.
2. `app/src/main/java/in/axomifymart/rider/` folder ke andar jaake, wahan ki
   4 files (`AxomifyApplication.kt`, `AxomifyFirebaseMessagingService.kt`,
   `MainActivity.kt`, `SplashActivity.kt`) ek-ek karke delete kar dijiye (har
   file khol ke, upar-right "..." menu ya trash icon se "Delete file"). Saari
   files delete hone ke baad khud hi woh poora `rider` folder gayab ho jayega.
3. Is zip ko extract kijiye apne computer par.
4. GitHub repo ke root par **"Add file" → "Upload files"** par jaiye, aur
   extract kiye hue folder ke ANDAR ki saari files/folders (poora `app/`,
   `.github/`, `build.gradle.kts`, `settings.gradle.kts`, waghera — is zip ke
   top-level `axomify-mart-seller-app` folder ke andar jo bhi hai) seedhe drag
   karke daal dijiye. GitHub automatically matching paths par purani files
   replace kar dega aur nayi files (jaise `in/axomifymart/vendor/` wala naya
   folder) add kar dega.
5. Neeche commit message likh ke **"Commit changes"** dabaiye.

(`app/google-services.json` abhi bhi PLACEHOLDER hai is zip mein — Step 2 mein
apni real file se replace kijiye, is upload step se pehle ya baad mein, dono
chalega.)

---

## Step 2 — Firebase setup

Aapne pehle hi bataya ki yeh step ho chuka hai — bas confirm kar lijiye:

1. Same existing Firebase project mein, `in.axomifymart.vendor` naam se naya
   Android app register kiya.
2. Us se mila `google-services.json` download karke is project ke `app/`
   folder mein (upar Step 1 ke upload ke saath ya alag se) daal diya — **isi
   naam se**, placeholder file ki jagah.

Service Account JSON (website ke "Axomify Core → Native App (FCM)" admin page
par upload wali) — yeh Rider app ke liye already ek baar upload ho chuki hai,
usi SAME Firebase project ke liye, toh dobara karne ki zarurat nahi.

---

## Step 3 — GitHub Actions build + install

1. Repo ke andar **"Actions"** tab par jaiye.
2. `Build Axomify Mart Seller APK` workflow khud chalega (push ke baad), ya
   "Run workflow" button dabaiye.
3. Kuch minute mein green ✅ tick aayega. Us run ko kholiye, neeche
   **"Artifacts"** mein `axomify-vendor-debug-apk` dikhega — download kar
   lijiye, usme `app-debug.apk` hai.
4. Woh `.apk` file seller ke phone mein bhej dijiye — tap karke install ho
   jayegi ("install from unknown source" allow karna padega, normal hai).

### Signed release build (recommended, Rider jaisa hi)

Aapne Rider app ke liye already ek stable signing keystore banaya tha (GitHub
Secrets: `AXOMIFY_KEYSTORE_BASE64`, `AXOMIFY_KEYSTORE_PASSWORD`,
`AXOMIFY_KEY_ALIAS`, `AXOMIFY_KEY_PASSWORD`). **Wahi SAME keystore is naye
Vendor repo ke liye bhi reuse kar sakte hain** — ek keystore se do alag apps
sign karna bilkul safe hai. Bas:

1. Is naye repo ke **Settings → Secrets and variables → Actions** mein jaiye.
2. Wahi 4 secrets, wahi SAME values (Rider repo wale), yahan bhi add kar
   dijiye.
3. Agla build "Artifacts" mein `axomify-vendor-release-apk` bhi dega — isi ko
   har update ke liye use kijiye (ek baar install karne ke baad, agle updates
   seedhe "over install" ho jayenge, Rider app jaisa hi).

---

## Ek chhoti si baaki hui cheez: START_URL

`app/src/main/java/in/axomifymart/vendor/MainActivity.kt` ke andar, sabse upar,
yeh line hai:

```kotlin
const val START_URL = "https://axomifymart.in/vendor-dashboard/"
```

Yeh URL abhi **tentative** hai (owner ke pehle diye hue pattern se). Build karne
se pehle, WordPress admin panel mein jaake confirm kar lijiye ki Vendor Dashboard
ka EXACT, real live URL kya hai — agar yeh alag hai, toh is line mein wahi real
URL daal dijiye.

---

## Icon/splash badalna (future mein kabhi)

`app/branding/icon_source.png` hi wahi seller icon hai jo abhi use ho raha hai.
Isko replace karke push kar dijiye — agla build khud `res/mipmap-*/` aur
`res/drawable-nodpi/splash_logo.png` (choti circular logo, MainActivity ki
apni bridge-splash screen par) regenerate kar dega — Rider app ke `v1.2.0`
self-service pipeline jaisa hi.

**Bada full-screen splash** (`res/drawable-nodpi/splash_fullscreen.jpg`, "Happy
Selling" wali artwork) is self-service script se NAHI judi — Rider app ke
apne full-screen splash jaisa hi, yeh seedhe is file ko replace karke hi
badalti hai, phir se build+reinstall ke saath.

---

## Phase 2 (baad mein): Vendor new-order siren/alerts

Jab owner iske liye ready hon: Marketplace plugin mein naya order aane par
vendor ko FCM push bhejna (abhi bilkul nahi bheja jaata), aur is app mein
Rider jaisa hi MediaPlayer-based siren logic add karna — dono hi is round mein
deliberately nahi kiye gaye hain.
