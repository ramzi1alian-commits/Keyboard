# مِرسال — Mirsal

## المرحلة الأولى — Android APK

هذه النسخة هي تحديث المرحلة الأولى من مِرسال:

- تطبيق Android مستقل.
- يعمل بدون خادم وبدون صلاحية INTERNET.
- AES-256-GCM + PBKDF2-SHA-256.
- منع لقطات الشاشة ومعظم التقاط الشاشة عبر `FLAG_SECURE`.
- تحميل ملفات التطبيق محليًا باستخدام `WebViewAssetLoader`.
- لا يعتمد GitHub Actions على وجود `gradlew` داخل المستودع؛ يتم تجهيز Gradle تلقائيًا أثناء البناء.
- إنشاء APK Debug تلقائيًا من GitHub Actions.

## بناء APK من GitHub

1. ارفع محتويات هذا المجلد إلى مستودع GitHub.
2. افتح تبويب **Actions**.
3. اختر **Build Mirsal APK**.
4. اضغط **Run workflow**.
5. بعد اكتمال البناء افتح الـ Artifact باسم `mirsal-debug-apk` لتحميل APK.

يمكن أيضًا إنشاء البناء تلقائيًا عند دفع Tag يبدأ بـ `v` مثل `v1.0.0`.

> ملاحظة: نسخة Debug مناسبة للاختبار. إصدار Play Store يحتاج لاحقًا إلى إعداد توقيع Release ومفاتيح GitHub Secrets.

## بنية المشروع

```text
mirsal/
├── web/                         # نسخة الويب/PWA
├── android/                     # مشروع Android
│   └── app/
│       └── src/main/
│           ├── java/com/mirsal/app/
│           ├── res/
│           └── assets/
└── .github/workflows/
    └── build-apk.yml
```

## المرحلة التالية

بعد تثبيت نسخة Android يمكن إضافة تشفير الملفات، QR، بصمة المفتاح، ثم PIN/بصمة الجهاز.
