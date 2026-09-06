package com.securekeyboard.app

/**
 * Compatibility marker for the crypto layer.
 *
 * Android ships its own security providers and their provider named "BC"
 * is not the same thing as the application's bundled Bouncy Castle library.
 * Pinning Android calls to provider name "BC" caused provider-dependent
 * failures on some Android 8 and Android 14 devices. The application now
 * uses the platform provider selection for standard EC/ECDH/AES-GCM/HMAC
 * primitives, while Bouncy Castle remains a direct library dependency for
 * Argon2id.
 */
object CryptoProvider {
    /** Kept for source compatibility with older classes; intentionally a no-op. */
    fun ensureRegistered() = Unit
}
