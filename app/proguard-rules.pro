# Keep the IME service and its manifest-declared metadata resolvable -
# InputMethodService components are found by the system via reflection-ish
# package scanning, so don't let R8 rename/strip them.
-keep class com.securekeyboard.app.SecureInputMethodService { *; }
-keep class com.securekeyboard.app.** extends android.app.Service

# Standard AndroidX / Material keep rules
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }

# Bouncy Castle (Argon2id) uses some reflection internally for provider
# registration - keep it intact under R8.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Don't warn about missing optional annotation classes pulled in transitively
-dontwarn org.jetbrains.annotations.**
