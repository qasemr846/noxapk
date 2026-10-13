# NOX برای اندروید (اتصال VPN داخل خود برنامه)

صفحه‌ی `app/src/main/assets/index.html` همان برنامه‌ی شماست. داخل APK یک WebView آن را اجرا می‌کند و دکمه‌ی «اتصال» به‌جای QR، مستقیم VPN را روشن می‌کند.

## مسیر کار (بدون Android Studio)

1. در github.com یک مخزن جدید بسازید (می‌تواند Private باشد).
2. **همه‌ی محتوای این پوشه** را در آن آپلود کنید، از جمله پوشه‌ی مخفی `.github`.
   اگر آپلود از مرورگر پوشه‌ی `.github` را نگرفت: در GitHub «Add file ← Create new file» را بزنید،
   نام فایل را `.github/workflows/build.yml` بگذارید و محتوای فایل `build.yml` را در آن بچسبانید.
3. به تب **Actions** بروید، «Build NOX APK» را انتخاب کنید و **Run workflow** را بزنید (حدود ۵ تا ۱۰ دقیقه).
4. پس از سبز شدن، در پایین صفحه‌ی اجرا، آرتیفکت **NOX-apk** را دانلود کنید. داخل zip فایل `app-debug.apk` است.
5. APK را روی گوشی نصب کنید (اجازه‌ی «نصب از منابع ناشناس» لازم است). بار اول اندروید اجازه‌ی VPN را می‌پرسد.

اگر Build قرمز شد، متن خطای همان مرحله را برای من بفرستید تا درستش کنم.

## مسیر کار با Android Studio (ویندوز)

1. Android Studio و Git را نصب کنید. در Android Studio از `Settings ← Languages & Frameworks ← Android SDK ← SDK Tools` گزینه‌ی **NDK (Side by side)** را تیک بزنید و Apply کنید.
2. zip را باز کنید. در پوشه‌ی پروژه یک Command Prompt باز کنید و بزنید:
   `git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel`
3. فایل `Xray-android-arm64-v8a.zip` را از https://github.com/XTLS/Xray-core/releases/latest دانلود و باز کنید. فایل `xray` (بدون پسوند) را به
   `app/src/main/jniLibs/arm64-v8a/libxray.so` کپی کنید (پوشه را بسازید، نام فایل را عوض کنید).
4. در Android Studio گزینه‌ی `File ← Open` و انتخاب پوشه‌ی پروژه. صبر کنید Gradle Sync تمام شود.
5. گوشی را با USB وصل کنید (Developer options و USB debugging روشن باشد) و دکمه‌ی Run را بزنید.
   یا برای APK: `Build ← Build Bundle(s) / APK(s) ← Build APK(s)`، سپس فایل در `app/build/outputs/apk/debug/app-debug.apk` است.

## چطور کار می‌کند

```
ترافیک گوشی → VpnService (tun) → hev-socks5-tunnel → Xray (SOCKS 127.0.0.1:10808) → سرور شما
```

- `XrayConfig.java`: لینک vless / vmess / trojan / ss را به کانفیگ Xray تبدیل می‌کند (tcp، ws، grpc، tls، reality).
- `NoxVpnService.java`: تونل را می‌سازد، Xray را اجرا می‌کند و سرعت و مصرف را به صفحه می‌فرستد.
- خود برنامه از VPN مستثنا است تا ارتباط Xray با سرور در حلقه نیفتد.
- ذخیره‌ی خودکار: همان localStorage برنامه‌ی شماست و داخل حافظه‌ی خود اپ می‌ماند. پشتیبان‌گیری فایل JSON را در Downloads ذخیره می‌کند.

## محدودیت‌ها

- فقط معماری arm64 (تقریباً همه‌ی گوشی‌های امروزی). گوشی‌های ۳۲ بیتی قدیمی پشتیبانی نمی‌شوند.
- IPv6 تونل نمی‌شود.
- hysteria2 و tuic پشتیبانی نمی‌شوند.
- APK با کلید debug امضا می‌شود؛ برای استفاده‌ی شخصی کافی است ولی برای انتشار در Play Store مناسب نیست.
