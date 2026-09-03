package com.securekeyboard.app

import android.util.Base64
import java.security.PublicKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * CryptoEngineV2
 *
 * Device-bound message encryption: the final AES key depends on BOTH
 * - the visible passphrase (same role as in CryptoEngine.kt today), AND
 * - a shared secret derived from THIS device's Keystore-resident
 *   private key + the specific contact's public key (see DeviceIdentity.kt)
 *
 * Consequence, verified by design: intercepting the passphrase AND the
 * ciphertext together (e.g. both sent over WhatsApp) is NOT sufficient
 * to decrypt. The missing ingredient - the recipient's private key -
 * never leaves their device's secure hardware, so it never travels
 * over any channel to intercept in the first place.
 *
 * This does NOT replace CryptoEngine.kt - that remains available as a
 * simpler passphrase-only mode (e.g. for encrypting a personal note to
 * yourself, where there's no "other device" to bind to). This class is
 * specifically for contact-to-contact messages. See EncryptActivity's
 * mode selector.
 *
 * ONE-TIME SETUP REQUIRED per contact: both sides must exchange public
 * keys once (DeviceIdentity.myPublicKeyBase64()) via any channel before
 * the first message - a QR code scan in person is the strongest option
 * (prevents a man-in-the-middle substituting their own public key
 * during the exchange). See ContactPairingActivity (to be added).
 */
object CryptoEngineV2 {

    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BYTES = 32
    const val FORMAT_VERSION: Byte = 2
    private const val HEADER_LENGTH = 1 + 1 + 8 // version + expiry flag + expiry epoch seconds

    /**
     * HKDF-Extract-and-Expand (RFC 5869), combining the ECDH shared
     * secret with the human passphrase. Both must be correct for the
     * output to match on the recipient's side - get either one wrong
     * and AES-GCM's auth tag simply fails to verify (see decrypt()).
     */
    private fun deriveMessageKey(sharedSecret: ByteArray, passphraseChars: CharArray): ByteArray {
        val ikm = sharedSecret + CryptoEngine.charsToUtf8Bytes(passphraseChars)
        val salt = "SecureKeyboard-v1-e2e-salt".toByteArray(Charsets.UTF_8)
        val info = "SecureKeyboard-message-key-v1".toByteArray(Charsets.UTF_8)

        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(ikm)

        val okmMac = Mac.getInstance("HmacSHA256")
        okmMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val t = okmMac.doFinal(info + byteArrayOf(1))
        return t.copyOf(KEY_LENGTH_BYTES)
    }

    /**
     * @param recipientPublicKey the contact you're sending TO (from a
     *   prior one-time pairing exchange - see class doc)
     */
    fun encrypt(
        textChars: CharArray,
        passphraseChars: CharArray,
        recipientPublicKey: PublicKey,
        expirySeconds: Long? = null
    ): String {
        val sharedSecret = DeviceIdentity.computeSharedSecret(recipientPublicKey)
        val keyBytes = deriveMessageKey(sharedSecret, passphraseChars)
        val plainBytes = CryptoEngine.charsToUtf8Bytes(textChars)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        try {
            val header = ByteBuffer.allocate(HEADER_LENGTH)
                .put(FORMAT_VERSION)
                .put(if (expirySeconds != null) 1 else 0)
                .putLong(if (expirySeconds != null) (System.currentTimeMillis() / 1000L) + expirySeconds else 0L)
                .array()
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val cipherBytes = cipher.doFinal(plainBytes)
            val combined = header + iv + cipherBytes
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            Arrays.fill(keyBytes, 0)
            Arrays.fill(plainBytes, 0)
            Arrays.fill(sharedSecret, 0)
        }
    }

    /**
     * @param senderPublicKey the contact who SENT this message (from
     *   the same one-time pairing - ECDH is symmetric: my_priv+their_pub
     *   equals their_priv+my_pub, so no separate "recipient key" needed)
     */
    fun decrypt(
        b64: String,
        passphraseChars: CharArray,
        senderPublicKey: PublicKey
    ): CharArray {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        require(combined.size > HEADER_LENGTH + IV_LENGTH) { "ciphertext too short" }
        require(combined[0] == FORMAT_VERSION) { "unsupported format version" }

        val header = combined.copyOfRange(0, HEADER_LENGTH)
        val headerBuffer = ByteBuffer.wrap(header)
        headerBuffer.get()
        val expiryFlag = headerBuffer.get().toInt()
        val expiryEpochSeconds = headerBuffer.long
        if (expiryFlag != 0) {
            require(expiryEpochSeconds > 0L) { "invalid expiry" }
            if (System.currentTimeMillis() / 1000L >= expiryEpochSeconds) {
                throw ExpiredMessageException()
            }
        }
        val iv = combined.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + IV_LENGTH)
        val cipherBytes = combined.copyOfRange(HEADER_LENGTH + IV_LENGTH, combined.size)

        val sharedSecret = DeviceIdentity.computeSharedSecret(senderPublicKey)
        val keyBytes = deriveMessageKey(sharedSecret, passphraseChars)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val plainBytes = cipher.doFinal(cipherBytes) // throws AEADBadTagException on wrong key
            try {
                val charBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
                val chars = CharArray(charBuffer.remaining())
                charBuffer.get(chars)
                return chars
            } finally {
                Arrays.fill(plainBytes, 0)
            }
        } finally {
            Arrays.fill(keyBytes, 0)
            Arrays.fill(sharedSecret, 0)
        }
    }
    class ExpiredMessageException : Exception()
}
