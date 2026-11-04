# Icon / Splash change -- sirf file replace kariye, kuch aur nahi

Yahan **3 files** rakhiye (teeno optional hain -- jo change karna hai sirf
wahi daaliye, baaki chhod dijiye):

- `icon_source.png` -- home-screen par dikhne wala app icon banega.
- `splash_source.png` -- app khulte waqt dikhne wala **chhota, gol splash
  logo** banega (WebView ka pehla page load hote waqt dikhta hai, circular
  crop ho jayega automatically).
- `splash_fullscreen_source.png` (ya `.jpg`/`.jpeg`) -- app kholte hi
  **sabse pehle** jo poori-screen artwork dikhti hai (SplashActivity), woh
  banega. **v1.3.0 se yeh bhi fully self-service hai** -- pehle isko badalne
  ke liye ek developer (Android Studio) chahiye hota tha, ab nahi.

**Bas itna karna hai**: purani image delete kijiye (agar hai), nayi image
isi exact naam se yahan upload kar dijiye, phir GitHub par commit/push kar
dijiye. Agla build (Actions tab mein khud chalega, ya "Run workflow"
dabaiye) is folder ko dekh kar automatically saari zaroori sizes khud bana
lega -- aapko kisi aur file (mipmap folders, XML) ko haath nahi lagana.

**Image kaisi honi chahiye**:
- `icon_source.png` / `splash_source.png`: roughly square (chौkor), kam se
  kam 512x512 pixel, PNG ya JPG.
- `splash_fullscreen_source.*`: koi bhi size/ratio chalega -- app
  automatically bade se bade size tak scale karke, beech se crop karke, poori
  screen fill kar dega (kabhi stretch/distort nahi hoga, kabhi khaali
  border nahi aayega) -- lekin best quality ke liye ek sharp, high-resolution
  (1080x1920 ya usse bada, portrait) image dena sabse accha rahega.

**Agar yeh folder khaali hai** (koi file nahi hai), toh koi problem nahi --
build wahi purani icon/splash use karega jo already app mein hai. Teeno
files bilkul independent hain -- sirf ek ko badalna baaki do ko touch nahi
karta.

Change ke baad bhi app ko dobara build + phone par poora uninstall-reinstall
karna hoga (yeh website ki tarah turant live update nahi hota, kyunki yeh
app ke andar ki asli file hai) -- lekin file taiyaar karne ka manual kaam ab
poori tarah khatam ho gaya hai, kisi bhi developer/Android Studio ki
zaroorat nahi.
