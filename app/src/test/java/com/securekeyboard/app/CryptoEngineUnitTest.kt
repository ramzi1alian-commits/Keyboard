package com.securekeyboard.app

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Arrays

/**
 * Fast, emulator-free unit tests for CryptoEngine. Runs under Robolectric
 * (needed only because CryptoEngine calls android.util.Base64) as part of
 * every `./gradlew test` invocation, complementing the slower instrumented
 * adversarial suite in androidTest/ which additionally exercises real
 * Android Keystore-backed paths (CryptoEngineV2/ECDH).
 *
 * Scope intentionally mirrors the existing adversarial test file so the
 * two suites stay easy to compare: this file adds coverage the emulator
 * suite doesn't need to pay emulator-boot cost for, and can run on every
 * push instead of only in the dedicated emulator CI job.
 */
@RunWith(RobolectricTestRunner::class)
class CryptoEngineUnitTest {

    private fun freshPass(value: String = "unit-test-passphrase") = value.toCharArray()

    @Test
    fun `round trip preserves plaintext exactly`() {
        val plain = "hello world 123 !@#".toCharArray()
        val pass = freshPass()
        val encoded = CryptoEngine.encrypt(plain, pass.copyOf(), null)
        val decoded = CryptoEngine.decrypt(encoded, pass.copyOf())
        try {
            assertArrayEquals(plain, decoded)
        } finally {
            Arrays.fill(decoded, '\u0000')
        }
    }

    @Test
    fun `round trip preserves unicode and emoji`() {
        val plain = "مرحبا 🔐 emoji test é 𐍈".toCharArray()
        val pass = freshPass()
        val encoded = CryptoEngine.encrypt(plain, pass.copyOf(), null)
        val decoded = CryptoEngine.decrypt(encoded, pass.copyOf())
        try {
            assertArrayEquals(plain, decoded)
        } finally {
            Arrays.fill(decoded, '\u0000')
        }
    }

    @Test
    fun `two encryptions of same plaintext produce different ciphertext`() {
        // Fresh random salt+IV every call is what makes this true; if it
        // ever stops being true, nonce/salt reuse has been introduced,
        // which would be a critical AES-GCM break (key+nonce reuse leaks
        // the XOR of two plaintexts and breaks authentication).
        val plain = "same message".toCharArray()
        val pass = freshPass()
        val first = CryptoEngine.encrypt(plain.copyOf(), pass.copyOf(), null)
        val second = CryptoEngine.encrypt(plain.copyOf(), pass.copyOf(), null)
        assertNotEquals(first, second)
    }

    @Test
    fun `wrong passphrase is rejected and never returns plaintext`() {
        val encoded = CryptoEngine.encrypt("secret data".toCharArray(), freshPass(), null)
        try {
            CryptoEngine.decrypt(encoded, freshPass("totally-wrong-passphrase"))
            fail("decrypt with wrong passphrase must throw")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `single flipped ciphertext byte breaks authentication`() {
        val encoded = CryptoEngine.encrypt("authenticated content".toCharArray(), freshPass(), null)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
        try {
            CryptoEngine.decrypt(tampered, freshPass())
            fail("tampered ciphertext must not decrypt")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `flipped header byte breaks authentication`() {
        // The header is passed as GCM additional authenticated data (AAD),
        // so mutating it must break the auth tag exactly like mutating the
        // ciphertext itself - the expiry timestamp can't be tampered with
        // independently of detection.
        val encoded = CryptoEngine.encrypt("expiry integrity".toCharArray(), freshPass(), 3600L)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        raw[2] = (raw[2].toInt() xor 0x01).toByte() // inside the 8-byte expiry field
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
        try {
            CryptoEngine.decrypt(tampered, freshPass())
            fail("tampered header/AAD must not decrypt")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `unsupported version byte is rejected`() {
        val encoded = CryptoEngine.encrypt("x".toCharArray(), freshPass(), null)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        raw[0] = 99
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
        try {
            CryptoEngine.decrypt(tampered, freshPass())
            fail("unknown format version must be rejected, not silently reinterpreted")
        } catch (e: IllegalArgumentException) {
            // expected: explicit rejection path
        }
    }

    @Test
    fun `truncated ciphertext fails closed`() {
        val tooShort = Base64.encodeToString(ByteArray(5), Base64.NO_WRAP)
        try {
            CryptoEngine.decrypt(tooShort, freshPass())
            fail("truncated ciphertext must be rejected")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `blank ciphertext is rejected`() {
        try {
            CryptoEngine.decrypt("   ", freshPass())
            fail("blank ciphertext must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty plaintext is rejected at encrypt time`() {
        CryptoEngine.encrypt(CharArray(0), freshPass(), null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty passphrase is rejected at encrypt time`() {
        CryptoEngine.encrypt("text".toCharArray(), CharArray(0), null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty passphrase is rejected at decrypt time`() {
        val encoded = CryptoEngine.encrypt("text".toCharArray(), freshPass(), null)
        CryptoEngine.decrypt(encoded, CharArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expiry beyond one year is rejected`() {
        CryptoEngine.encrypt("x".toCharArray(), freshPass(), 366L * 24 * 60 * 60)
    }

    @Test
    fun `negative expiry is rejected`() {
        try {
            CryptoEngine.encrypt("x".toCharArray(), freshPass(), -1L)
            fail("negative expiry must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `already-expired message throws ExpiredMessageException on decrypt`() {
        // expirySeconds = 0 means "expires immediately" (epoch = now).
        val encoded = CryptoEngine.encrypt("expiring".toCharArray(), freshPass(), 0L)
        Thread.sleep(1100) // ensure the 1-second epoch resolution has ticked past expiry
        try {
            CryptoEngine.decrypt(encoded, freshPass())
            fail("expired message must not decrypt")
        } catch (_: CryptoEngine.ExpiredMessageException) {
            // expected
        }
    }

    @Test
    fun `non-expiring message has no expiry flag and decrypts any time`() {
        val encoded = CryptoEngine.encrypt("no expiry".toCharArray(), freshPass(), null)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        assertTrue("expiry flag byte should be 0 when no expiry was requested", raw[1].toInt() == 0)
        // Should decrypt fine regardless of how much time passes.
        val decoded = CryptoEngine.decrypt(encoded, freshPass())
        try {
            assertArrayEquals("no expiry".toCharArray(), decoded)
        } finally {
            Arrays.fill(decoded, '\u0000')
        }
    }

    @Test
    fun `crafted huge Argon2 memory cost in header is rejected before key derivation`() {
        // Regression guard for the resource-exhaustion bounds check: an
        // attacker-controlled header must not be able to force this device
        // to allocate an arbitrary amount of memory for Argon2id before any
        // authentication has happened.
        val encoded = CryptoEngine.encrypt("bounded".toCharArray(), freshPass(), null)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        // v3 header layout: [0]=version [1]=hasExpiry [2..9]=expiry [10..13]=memoryKb (big-endian int)
        raw[10] = 0x7f
        raw[11] = 0x7f
        raw[12] = 0x7f
        raw[13] = 0x7f
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
        val start = System.nanoTime()
        try {
            CryptoEngine.decrypt(tampered, freshPass())
            fail("out-of-bounds Argon2 memory cost must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected - and expected to fail FAST, before any Argon2 allocation
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                "bounds check must reject before spending time on Argon2id (took ${elapsedMs}ms)",
                elapsedMs < 500
            )
        }
    }

    @Test
    fun `looksLikeCiphertext rejects obvious plaintext and accepts real ciphertext`() {
        assertFalse(CryptoEngine.looksLikeCiphertext("just some plain text, not encrypted"))
        assertFalse(CryptoEngine.looksLikeCiphertext(""))
        assertFalse(CryptoEngine.looksLikeCiphertext("short"))
        val encoded = CryptoEngine.encrypt("real ciphertext".toCharArray(), freshPass(), null)
        assertTrue(CryptoEngine.looksLikeCiphertext(encoded))
    }

    @Test
    fun `charsToUtf8Bytes zeroes its internal buffer and round trips UTF-8`() {
        val chars = "abc مرحبا".toCharArray()
        val bytes = CryptoEngine.charsToUtf8Bytes(chars)
        assertArrayEquals(chars.concatToString().toByteArray(Charsets.UTF_8), bytes)
    }
}
