package com.securekeyboard.app

import android.content.Context
import java.security.PublicKey
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Single cryptographic identity layer for contact-bound content.
 *
 * Every contact-bound operation starts from the same device identity and the
 * selected contact public key through ECDH. The resulting secret is then
 * domain-separated with HKDF/HMAC-SHA256 so messages and files never reuse
 * the same AES key, while still belonging to the exact same contact identity.
 *
 * A passphrase is retained as an additional secret factor for compatibility
 * with the secure keyboard session. Nothing is sent to a server.
 */
object ContactCrypto {
    private const val KEY_LENGTH_BYTES = 32

    private const val MESSAGE_SALT = "SecureKeyboard-v1-e2e-salt"
    private const val MESSAGE_INFO = "SecureKeyboard-message-key-v1"
    private const val FILE_SALT = "SecureKeyboard-file-salt-v1"
    private const val FILE_INFO = "SecureKeyboard-file-key-v1"

    enum class Purpose(val salt: String, val info: String) {
        MESSAGE(MESSAGE_SALT, MESSAGE_INFO),
        FILE(FILE_SALT, FILE_INFO)
    }

    fun deriveAes256KeyFromEphemeralPrivate(
        ephemeralPrivateKey: java.security.PrivateKey,
        recipientPublicKey: PublicKey,
        passphraseChars: CharArray,
        purpose: Purpose
    ): ByteArray = deriveFromSharedSecretForInternal(
        DeviceIdentity.computeSharedSecretWithPrivateKey(ephemeralPrivateKey, recipientPublicKey),
        passphraseChars,
        purpose
    )

    fun deriveAes256KeyFromEphemeralPublic(
        context: Context,
        senderEphemeralPublicKey: PublicKey,
        passphraseChars: CharArray,
        purpose: Purpose
    ): ByteArray = deriveFromSharedSecretForInternal(
        DeviceIdentity.computeSharedSecret(context, senderEphemeralPublicKey),
        passphraseChars,
        purpose
    )

    fun deriveFromSharedSecretForInternal(
        sharedSecret: ByteArray,
        passphraseChars: CharArray,
        purpose: Purpose
    ): ByteArray {
        require(passphraseChars.isNotEmpty()) { "passphrase is empty" }
        val passBytes = CryptoEngine.charsToUtf8Bytes(passphraseChars)
        val ikm = sharedSecret + passBytes
        val salt = purpose.salt.toByteArray(Charsets.UTF_8)
        val info = purpose.info.toByteArray(Charsets.UTF_8)
        return try {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = extract.doFinal(ikm)
            try {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(prk, "HmacSHA256"))
                expand.doFinal(info + byteArrayOf(1)).copyOf(KEY_LENGTH_BYTES)
            } finally { Arrays.fill(prk, 0) }
        } finally {
            Arrays.fill(sharedSecret, 0)
            Arrays.fill(passBytes, 0)
            Arrays.fill(ikm, 0)
            Arrays.fill(salt, 0)
            Arrays.fill(info, 0)
        }
    }

    /** Derives the AES-256 key from the same contact identity used everywhere. */
    fun deriveAes256Key(
        context: Context,
        contactPublicKey: PublicKey,
        passphraseChars: CharArray,
        purpose: Purpose
    ): ByteArray {
        require(passphraseChars.isNotEmpty()) { "passphrase is empty" }

        val sharedSecret = DeviceIdentity.computeSharedSecret(context, contactPublicKey)
        val passBytes = CryptoEngine.charsToUtf8Bytes(passphraseChars)
        val ikm = sharedSecret + passBytes
        val salt = purpose.salt.toByteArray(Charsets.UTF_8)
        val info = purpose.info.toByteArray(Charsets.UTF_8)

        return try {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = extract.doFinal(ikm)
            try {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(prk, "HmacSHA256"))
                expand.doFinal(info + byteArrayOf(1)).copyOf(KEY_LENGTH_BYTES)
            } finally {
                Arrays.fill(prk, 0)
            }
        } finally {
            Arrays.fill(sharedSecret, 0)
            Arrays.fill(passBytes, 0)
            Arrays.fill(ikm, 0)
        }
    }
}
