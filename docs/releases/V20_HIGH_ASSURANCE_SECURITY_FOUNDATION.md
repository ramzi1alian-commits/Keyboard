# SecureKeyboard V20 — High-Assurance Security Foundation

## الهدف
V20 يرفع المشروع من مرحلة "ميزات أمنية قوية" إلى أساس قابل للتدقيق الرسمي: تقليل سطح الهجوم، تثبيت حدود الثقة، منع التسريبات الواضحة، وإضافة بوابة تدقيق آلي في CI.

> **مهم:** هذه النسخة ليست شهادة عسكرية أو FIPS 140-3. الشهادة/الاعتماد يحتاجان مختبرًا وجهة اعتماد مستقلة واختبارات رسمية. الهدف هنا هو بناء المنتج بطريقة تصلح كأساس لمثل هذا التقييم.

## ما تم تنفيذه في V20

1. **Network attack-surface hardening**
   - لا توجد صلاحية `INTERNET`.
   - `android:usesCleartextTraffic="false"` مفعّل صراحةً.
   - Network Security Config موجود كطبقة دفاع إضافية.

2. **Backup / data-exfiltration hardening**
   - `allowBackup=false`.
   - قواعد Android القديمة وAndroid 12+ تستبعد بيانات التطبيق.

3. **Cryptographic path hardening**
   - AES-256-GCM للرسائل والملفات.
   - Argon2id للرسائل المعتمدة على عبارة المرور.
   - P-256 ECDH لمحتوى جهات الاتصال.
   - مفاتيح التخزين المحلي محمية عبر Android Keystore + AES-GCM.
   - تنظيف أفضل للذاكرة الحساسة في مسار تشفير الملفات عند الفشل.

4. **File integrity handling**
   - فك ملفات SKF لا يعتمد على `CipherInputStream`؛ تتم مصادقة GCM صراحةً عبر `doFinal()`.
   - أي فشل أثناء فك الملف يؤدي إلى حذف الملف المؤقت.

5. **Component / sharing hardening**
   - المكونات الداخلية غير مصدّرة.
   - نقطة استقبال ملفات SKF هي الاستثناء المقصود، وتستخدم FileProvider/SAF بدل المسارات العامة.

6. **Automated security gate**
   - `scripts/security_audit.sh` يفحص الصلاحيات، التشفير القديم، MD5/SHA-1، الشبكات الواضحة، Log calls، والمكونات المصدّرة.
   - GitHub Actions يشغّل التدقيق قبل بناء APK.

## ما لم ندّعِ تنفيذه بعد

- Forward Secrecy حقيقي لبروتوكول جهات الاتصال.
- بروتوكول مصادقة/تدوير مفاتيح كامل بمواصفات مكتوبة واختبارات خصمية.
- إثبات hardware-backed ECDH identity على كل OEM؛ المسار الحالي يحافظ على توافق API 24–34 باستخدام هوية EC برمجية مشفرة عند التخزين.
- اختبار اختراق مستقل، مراجعة كود مستقلة، fuzzing شامل، وقياسات side-channel.
- شهادة FIPS أو اعتماد عسكري.
- ترقية Android 16/API 36 واختبارها كـ Release Candidate؛ V20 يبقى على SDK 34 حتى لا نخلط تغيير منصة كبيرًا مع تغييرات الأمان الأساسية قبل اختباره.

## بوابات الإصدار القادمة

- **V21:** adversarial/fuzz testing للـ parsers والـ intents وملفات SKF.
- **V22:** Android hardening واختبارات API 24–36، مع الانتقال المدروس إلى target 36.
- **V23:** key-management hardening، key rotation، recovery/destruction semantics، وتوثيق دورة حياة المفاتيح.
- **V24:** بروتوكول Forward Secrecy لجهات الاتصال مع domain separation وanti-replay.
- **V25:** security review نهائي + threat model + security test report + release checklist.

## قاعدة ادعاءات الأمان
أي خاصية لا يوجد لها اختبار أو دليل قابل للتكرار لا تُوصف بأنها "مضمونة". وأي ادعاء "عسكري" أو "FIPS validated" يتطلب جهة تقييم واعتماد مستقلة.
