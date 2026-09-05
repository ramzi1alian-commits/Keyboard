package com.securekeyboard.app

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Contact-bound message encryption with per-message P-256 ECDHE. */
object CryptoEngineV2 {
    private const val IV_LENGTH = 12
    private const val MAX_EPHEMERAL_KEY_BYTES = 200
    private const val LEGACY_HEADER_LENGTH = 1 + 1 + 8
    private const val ECDHE_PREFIX_LENGTH = 1 + 1 + 8 + 2
    const val FORMAT_VERSION: Byte = 3
    private const val LEGACY_FORMAT_VERSION: Byte = 2

    fun encrypt(
        context: android.content.Context,
        textChars: CharArray,
        passphraseChars: CharArray,
        recipientPublicKey: PublicKey,
        expirySeconds: Long? = null
    ): String {
        require(passphraseChars.isNotEmpty()) { "passphrase is empty" }
        val ephemeral = DeviceIdentity.generateEphemeralKeyPair()
        val keyBytes = ContactCrypto.deriveAes256KeyFromEphemeralPrivate(
            ephemeral.private, recipientPublicKey, passphraseChars, ContactCrypto.Purpose.MESSAGE
        )
        val plainBytes = CryptoEngine.charsToUtf8Bytes(textChars)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val ephemeralBytes = ephemeral.public.encoded
        try {
            require(ephemeralBytes.size in 50..MAX_EPHEMERAL_KEY_BYTES) { "invalid ephemeral public key" }
            val expiryFlag: Byte = if (expirySeconds != null) 1 else 0
            if (expirySeconds != null) require(expirySeconds > 0) { "invalid expiry" }
            val header = ByteBuffer.allocate(ECDHE_PREFIX_LENGTH + ephemeralBytes.size)
                .put(FORMAT_VERSION)
                .put(expiryFlag)
                .putLong(if (expirySeconds != null) (System.currentTimeMillis() / 1000L) + expirySeconds else 0L)
                .putShort(ephemeralBytes.size.toShort())
                .put(ephemeralBytes)
                .array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val cipherBytes = cipher.doFinal(plainBytes)
            return Base64.encodeToString(header + iv + cipherBytes, Base64.NO_WRAP)
        } finally {
            Arrays.fill(keyBytes, 0); Arrays.fill(plainBytes, 0); Arrays.fill(iv, 0); Arrays.fill(ephemeralBytes, 0)
        }
    }

    fun decrypt(
        context: android.content.Context,
        b64: String,
        passphraseChars: CharArray,
        senderPublicKey: PublicKey
    ): CharArray {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        require(combined.size > LEGACY_HEADER_LENGTH + IV_LENGTH) { "ciphertext too short" }
        return when (combined[0]) {
            LEGACY_FORMAT_VERSION -> decryptLegacy(context, combined, passphraseChars, senderPublicKey)
            FORMAT_VERSION -> decryptEcdhe(context, combined, passphraseChars)
            else -> throw IllegalArgumentException("unsupported format version")
        }
    }

    private fun decryptEcdhe(
        context: android.content.Context,
        combined: ByteArray,
        passphraseChars: CharArray
    ): CharArray {
        require(combined.size >= ECDHE_PREFIX_LENGTH + 50 + IV_LENGTH + 16) { "ciphertext too short" }
        val headerPrefix = combined.copyOfRange(0, ECDHE_PREFIX_LENGTH)
        val buf = ByteBuffer.wrap(headerPrefix)
        buf.get()
        val expiryFlag = buf.get().toInt()
        val expiryEpochSeconds = buf.long
        val ephLen = buf.short.toInt() and 0xffff
        require(ephLen in 50..MAX_EPHEMERAL_KEY_BYTES) { "invalid ephemeral key length" }
        val headerLength = ECDHE_PREFIX_LENGTH + ephLen
        require(combined.size > headerLength + IV_LENGTH + 16) { "ciphertext too short" }
        val header = combined.copyOfRange(0, headerLength)
        val ephemeralBytes = combined.copyOfRange(ECDHE_PREFIX_LENGTH, headerLength)
        val iv = combined.copyOfRange(headerLength, headerLength + IV_LENGTH)
        val cipherBytes = combined.copyOfRange(headerLength + IV_LENGTH, combined.size)
        try {
            validateExpiry(expiryFlag, expiryEpochSeconds)
            val ephemeralPublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephemeralBytes))
            val keyBytes = ContactCrypto.deriveAes256KeyFromEphemeralPublic(context, ephemeralPublic, passphraseChars, ContactCrypto.Purpose.MESSAGE)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(header)
                val plainBytes = cipher.doFinal(cipherBytes)
                try { return utf8ToChars(plainBytes) } finally { Arrays.fill(plainBytes, 0) }
            } finally { Arrays.fill(keyBytes, 0) }
        } finally {
            Arrays.fill(headerPrefix,0); Arrays.fill(header,0); Arrays.fill(ephemeralBytes,0); Arrays.fill(iv,0); Arrays.fill(cipherBytes,0)
        }
    }

    private fun decryptLegacy(context: android.content.Context, combined: ByteArray, passphraseChars: CharArray, senderPublicKey: PublicKey): CharArray {
        val header = combined.copyOfRange(0, LEGACY_HEADER_LENGTH)
        try {
            val headerBuffer = ByteBuffer.wrap(header)
            headerBuffer.get()
            val expiryFlag = headerBuffer.get().toInt()
            val expiryEpochSeconds = headerBuffer.long
            validateExpiry(expiryFlag, expiryEpochSeconds)
            val iv = combined.copyOfRange(LEGACY_HEADER_LENGTH, LEGACY_HEADER_LENGTH + IV_LENGTH)
            val cipherBytes = combined.copyOfRange(LEGACY_HEADER_LENGTH + IV_LENGTH, combined.size)
            try {
                val keyBytes = ContactCrypto.deriveAes256Key(context, senderPublicKey, passphraseChars, ContactCrypto.Purpose.MESSAGE)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    cipher.updateAAD(header)
                    val plainBytes = cipher.doFinal(cipherBytes)
                    try { return utf8ToChars(plainBytes) } finally { Arrays.fill(plainBytes, 0) }
                } finally { Arrays.fill(keyBytes, 0) }
            } finally { Arrays.fill(iv,0); Arrays.fill(cipherBytes,0) }
        } finally { Arrays.fill(header,0) }
    }

    private fun validateExpiry(flag: Int, epoch: Long) {
        require(flag == 0 || flag == 1) { "invalid expiry flag" }
        if (flag != 0) {
            require(epoch > 0L) { "invalid expiry" }
            if (System.currentTimeMillis() / 1000L >= epoch) throw ExpiredMessageException()
        }
    }

    private fun utf8ToChars(bytes: ByteArray): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
        val chars = decoder.decode(ByteBuffer.wrap(bytes)).let { buffer -> CharArray(buffer.remaining()).also { buffer.get(it) } }
        return chars
    }

    class ExpiredMessageException : Exception()
}
