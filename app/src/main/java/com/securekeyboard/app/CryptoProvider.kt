package com.securekeyboard.app

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * FIXED (Android-8-specific decrypt failures: "key corrupted/invalid file"
 * reported only on Android 8, only when decrypting with a real contact
 * selected): every EC/ECDH/AES-GCM/HMAC getInstance() call in this app
 * used to omit the provider name (e.g. KeyAgreement.getInstance("ECDH")
 * instead of getInstance("ECDH", "BC")). Without an explicit provider, the
 * JVM/Android runtime picks whichever registered security provider offers
 * that algorithm first - and that pick is Android-version- and
 * device-dependent. Conscrypt/AndroidOpenSSL's EC support, and its
 * priority relative to whatever other providers a given OEM/OS build
 * registers, has genuinely differed across Android versions - notably
 * around the API 26-28 range, during Android's transition away from its
 * old bundled "BC" provider toward Conscrypt as the preferred EC/TLS
 * provider. Two devices on two different Android versions could silently
 * compute the same ECDH shared secret differently for the exact same key
 * pair, with no exception anywhere - the first visible symptom is the
 * GCM auth tag check failing on decrypt, which surfaces as a generic
 * "key corrupted / invalid file" error even though both sides' actual
 * keys and passphrase were correct the whole time.
 *
 * BouncyCastle is already a project dependency
 * (org.bouncycastle:bcprov-jdk18on) but, before this file, was only ever
 * used directly for its Argon2id classes (see CryptoEngine) - it was
 * never registered as a JCE Provider, so nothing else in the app actually
 * ran through it; every EC/AES/HMAC operation elsewhere was silently
 * going through whatever the OS happened to provide instead, with no way
 * to guarantee consistency between two different devices/versions.
 *
 * Registering and explicitly pinning "BC" everywhere (see DeviceIdentity,
 * ContactCrypto, SecureFileCrypto, CryptoEngine) makes every cryptographic
 * operation in this app behave byte-for-byte identically on every Android
 * version and every device, instead of depending on whatever the OS ships.
 */
object CryptoProvider {
    /** Pass as the second argument to every relevant *.getInstance(...) call. */
    const val NAME = "BC"

    @Volatile
    private var registered = false

    /**
     * Idempotent and safe to call from every object's init block (see
     * call sites) - only the first call actually touches the global
     * Security provider list.
     */
    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            if (Security.getProvider(NAME) == null) {
                // insertProviderAt(..., 1) = highest priority, so even a
                // getInstance() call elsewhere that forgets to pin "BC"
                // explicitly still resolves to BouncyCastle first, rather
                // than silently falling back to an OS-provided ordering
                // that could differ across Android versions.
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            registered = true
        }
    }
}
