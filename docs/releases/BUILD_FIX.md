# Build verification fix

The previous CI run failed in `Prefs.kt` because the file was missing its Android/AndroidX imports, which caused the subsequent `R`/resource references to cascade into errors. The three `Typeface.create(...)` calls in `SecureInputMethodService.kt` also now explicitly select the `Typeface` overload.

This package contains those fixes. GitHub Actions is configured to build with Gradle 8.4 and JDK 17.
