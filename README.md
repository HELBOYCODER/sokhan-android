# Sokhan (سخن) — Android

Speech-to-text for Android with a floating lips button: tap anywhere to talk,
tap again, and the transcript is typed into whatever field you are in. Uses
Google's free Web Speech engine (the same one Chrome uses) — no accounts, no
API keys, no local models.

**سخن برای اندروید**: دکمه لب روی صفحه — هرجا که می‌خواهید تایپ کنید، روی
دکمه بزنید، حرف بزنید و دوباره بزنید؛ متن خودکار در همان‌جا درج می‌شود.
موتور تشخیص، سرویس رایگان گفتار گوگل است (همان موتور کروم) — بدون حساب،
بدون کلید API، بدون مدل محلی.

## Install / نصب

1. Download `Sokhan-<version>.apk` from
   [Releases](https://github.com/HELBOYCODER/sokhan-app/releases) and install it
   (allow "install unknown apps" if asked).
2. Open سخن and grant microphone (and notification) permissions.
3. Tap **تنظیمات دسترسی‌پذیری** and enable **سخن** in the accessibility list.
4. The lips button appears on screen. Drag it anywhere. Tap to record,
   tap again to stop — the text is typed into the focused field.

1. فایل `Sokhan-<version>.apk` را از صفحه Releases دانلود و نصب کنید.
2. برنامه سخن را باز کنید و اجازه میکروفون را بدهید.
3. روی «تنظیمات دسترسی‌پذیری» بزنید و «سخن» را فعال کنید.
4. دکمه لب روی صفحه ظاهر می‌شود؛ جابه‌جایش کنید، بزنید و صحبت کنید،
   دوباره بزنید تا متن در محل تایپ درج شود.

## Notes / نکته‌ها

- Languages: فارسی (default), English, العربية, Türkçe — changeable in the app.
- Recording is held fully in memory; long continuous dictation is split only
  at genuinely quiet points (50 s preferred, 120 s hard ceiling), so words are
  never cut mid-stream — the same engine semantics as the desktop app.
- If no editable field is focused, the transcript is copied to the clipboard.
- Maximum single recording: 20 minutes.
- Requires Android 8.0+ (API 26).

- زبان‌ها: فارسی (پیش‌فرض)، انگلیسی، عربی، ترکی — از داخل برنامه تغییر کنید.
- صدای ضبط‌شده کامل در حافظه نگه داشته می‌شود؛ گفتار پیوسته‌ی طولانی فقط در
  نقاط ساکت تقسیم می‌شود (سقف نرم ۵۰ ثانیه، سقف سخت ۱۲۰ ثانیه)، پس هیچ
  کلمه‌ای نصف نمی‌شود — همان رفتار نسخه دسکتاپ.
- اگر فیلد متنی در حالت تمرکز نباشد، متن در کلیپ‌بورد کپی می‌شود.
- حداکثر طول هر ضبط: ۲۰ دقیقه.
- اندروید ۸ به بالا (API 26).

## Build

CI (GitHub Actions, ubuntu-latest) runs `gradle test assembleRelease` on every
push and publishes signed APKs on `v*` tags. Signing uses `keystore/sokhan.p12`
(PKCS12). No wrapper is committed; Gradle is pinned by the workflow.

## Provenance

Kotlin port of the Sokhan desktop engine (Tauri/Rust): the Google SDA
full-duplex protocol (`stt/Protocol.kt`), the streaming FLAC encoder
(`stt/Flac.kt`, verified byte-exact against the Rust implementation), and the
quality-aware chunk planner (`stt/Engine.kt`). MIT licensed, like Sokhan
desktop (which itself forked cjpais/Handy).
