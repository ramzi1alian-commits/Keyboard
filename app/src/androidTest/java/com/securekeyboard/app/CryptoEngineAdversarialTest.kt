package com.securekeyboard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Arrays

/**
 * V21 adversarial tests for the message crypto boundary.
 * These tests deliberately mutate untrusted ciphertext/header bytes and verify
 * that parsing/authentication fails closed rather than returning plaintext.
 */
@RunWith(AndroidJUnit4::class)
class CryptoEngineAdversarialTest {
    private val passphrase = "V21-test-passphrase".toCharArray()

    @Test
    fun roundTrip_unicode_and_empty_expiry() {
        val plain = "مرحبا 🔐 — zero trust / é / 𐍈".toCharArray()
        val encoded = CryptoEngine.encrypt(plain, passphrase.copyOf(), null)
        val decoded = CryptoEngine.decrypt(encoded, passphrase.copyOf())
        try {
            assertArrayEquals(plain, decoded)
        } finally {
            Arrays.fill(decoded, '\u0000')
            Arrays.fill(plain, '\u0000')
        }
    }

    @Test
    fun tampering_single_ciphertext_byte_is_rejected() {
        val encoded = CryptoEngine.encrypt("authenticated".toCharArray(), passphrase.copyOf(), null)
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        val tampered = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        assertDecryptFails(tampered)
        Arrays.fill(raw, 0)
    }

    @Test
    fun tampering_header_is_rejected() {
        val encoded = CryptoEngine.encrypt("header authenticated".toCharArray(), passphrase.copyOf(), null)
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        raw[0] = 2 // v3 -> legacy-looking version; parser must not reinterpret silently.
        val tampered = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        assertDecryptFails(tampered)
        Arrays.fill(raw, 0)
    }

    @Test
    fun malformed_short_ciphertext_fails_closed() {
        assertDecryptFails(android.util.Base64.encodeToString(ByteArray(3), android.util.Base64.NO_WRAP))
    }

    @Test
    fun malformed_argon2_parameters_are_rejected_before_derivation() {
        val encoded = CryptoEngine.encrypt("bounded".toCharArray(), passphrase.copyOf(), null)
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        // v3 header: version(0), expiry flag(1), expiry(2..9), memory(10..13)
        raw[10] = 0x7f
        raw[11] = 0x7f
        raw[12] = 0x7f
        raw[13] = 0x7f
        val tampered = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        assertDecryptFails(tampered)
        Arrays.fill(raw, 0)
    }

    @Test
    fun looks_like_ciphertext_rejects_plaintext_and_accepts_real_ciphertext() {
        assertFalse(CryptoEngine.looksLikeCiphertext("not encrypted text"))
        val encoded = CryptoEngine.encrypt("looks-like".toCharArray(), passphrase.copyOf(), null)
        assertTrue(CryptoEngine.looksLikeCiphertext(encoded))
    }

    @Test
    fun wrong_passphrase_is_rejected_without_plaintext() {
        val encoded = CryptoEngine.encrypt("secret".toCharArray(), passphrase.copyOf(), null)
        assertDecryptFails(encoded, "definitely-wrong".toCharArray())
    }

    private fun assertDecryptFails(encoded: String, pass: CharArray = passphrase.copyOf()) {
        try {
            CryptoEngine.decrypt(encoded, pass)
            throw AssertionError("Decryption unexpectedly succeeded")
        } catch (_: Exception) {
            // Expected: malformed, tampered, or wrongly keyed ciphertext is never plaintext.
        } finally {
            Arrays.fill(pass, '\u0000')
        }
    }
}
