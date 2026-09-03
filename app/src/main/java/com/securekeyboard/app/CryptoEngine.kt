package com.securekeyboard.app

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoEngine
 *
 * The actual encrypt/decrypt implementation, extracted out of
 * EncryptActivity so the SAME code path is used whether the user is on
 * the full encryption screen OR using the keyboard's own quick-encrypt
 * panel (see SecureInputMethodService's crypto overlay page). Nothing
 * about the algorithm, format, or security properties changed in this
 * extraction - see the original design notes below, carried over as-is.
 *
 * AES-256-GCM with an Argon2id-derived key, fully offline (no networking
 * code anywhere in this app, no INTERNET permission in the manifest).
 * Passphrases and plaintext are handled as CharArray/ByteArray, never
 * String, so callers can explicitly zero them after use.
 *
 * MESSAGE-EXPIRY: an optional expiry duration can be attached at encrypt
 * time. After that time, THIS APP will refuse to decrypt the message,
 * and the expiry value itself is authenticated (GCM "additional
 * authenticated data") so tampering with it breaks the auth tag. This
 * is protection against casual/accidental decryption after a deadline,
 * not an unconditional guarantee the ciphertext becomes unrecoverable -
 * see the full explanation that used to live here, now in
 * EncryptActivity's class doc.
 */
object CryptoEngine {

    const val IV_LENGTH = 12
    const val SALT_LENGTH = 16
    const val KEY_LENGTH_BYTES = 32 // AES-256

    // --- Ciphertext header versions ---
    //
    // v2 (legacy): 1 (version) + 1 (hasExpiry) + 8 (expiry epoch seconds) = 10 bytes.
    // Argon2id params for v2 messages are NOT stored in the header at all -
    // they were hardcoded constants at encrypt time, so decrypting an old
    // v2 message MUST reuse those exact old constants (LEGACY_V2_* below),
    // never whatever the current defaults happen to be. This is why the
    // version byte is now actually branched on, instead of just being
    // read-and-ignored as before.
    //
    // v3 (current): v2 header + 4 (Argon2 memory in KB) + 1 (iterations) +
    // 1 (parallelism) = 16 bytes. The KDF cost parameters travel WITH the
    // message, so raising the defaults in a future release never breaks
    // decrypting older v3 messages, and never requires another silent
    // hardcoded-legacy-branch the way this v2->v3 change did.
    const val HEADER_LENGTH_V2 = 10
    const val HEADER_LENGTH_V3 = 16
    const val FORMAT_VERSION: Byte = 3
    private const val VERSION_V2: Byte = 2
    private const val VERSION_V3: Byte = 3

    // Argon2id parameters used for every NEW encryption. Raised from the
    // previous 64 MB baseline to 256 MB: this app is fully offline and
    // encrypt/decrypt is a rare, deliberate, foreground action (not a
    // hot path like a login screen), so the extra ~1-2s and memory cost
    // on modern phones is a good trade for meaningfully higher resistance
    // to GPU/ASIC brute-forcing of the passphrase. Devices too old/low-RAM
    // to comfortably allocate 256 MB can still fall back - see
    // LOW_MEMORY_ARGON_MEMORY_KB below.
    private const val ARGON_MEMORY_KB = 262144 // 256 MB
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 1

    // Fallback for constrained devices (old/low-RAM), still well above the
    // previous 64 MB baseline. Chosen automatically by encrypt() based on
    // Runtime.getRuntime().maxMemory() - see chooseArgonMemoryKb().
    private const val REDUCED_ARGON_MEMORY_KB = 131072 // 128 MB
    private const val MIN_ARGON_MEMORY_KB = 32768 // 32 MB
    private const val MAX_ARGON_MEMORY_KB = ARGON_MEMORY_KB // 256 MB
    private const val MIN_ARGON_ITERATIONS = 2
    private const val MAX_ARGON_ITERATIONS = 6
    private const val MIN_ARGON_PARALLELISM = 1
    private const val MAX_ARGON_PARALLELISM = 4
    private const val MAX_CIPHERTEXT_BASE64_CHARS = 8 * 1024 * 1024

    // Legacy parameters - ONLY ever used to decrypt old v2 ciphertexts
    // that don't carry their own Argon2 params. Never used for encrypting
    // anything new. Do not "clean these up"; removing them would make
    // every message encrypted before this change permanently undecryptable.
    private const val LEGACY_V2_ARGON_MEMORY_KB = 65536
    private const val LEGACY_V2_ARGON_ITERATIONS = 3
    private const val LEGACY_V2_ARGON_PARALLELISM = 1

    class ExpiredMessageException : Exception()

    /**
     * Encodes a CharArray as UTF-8 bytes WITHOUT ever allocating an
     * intermediate String (String.toByteArray() would create one, and
     * Strings can't be wiped from memory).
     */
    fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) {
            Arrays.fill(byteBuffer.array(), 0)
        }
        return bytes
    }

    fun deriveKey(
        passChars: CharArray,
        salt: ByteArray,
        memoryKb: Int = ARGON_MEMORY_KB,
        iterations: Int = ARGON_ITERATIONS,
        parallelism: Int = ARGON_PARALLELISM
    ): ByteArray {
        val passBytes = charsToUtf8Bytes(passChars)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val keyBytes = ByteArray(KEY_LENGTH_BYTES)
            generator.generateBytes(passBytes, keyBytes)
            return keyBytes
        } finally {
            Arrays.fill(passBytes, 0)
        }
    }

    /**
     * Picks the Argon2id memory cost for a NEW encryption based on how
     * much heap this process can actually use. Argon2id allocates its
     * full memory cost as a single contiguous buffer; asking for 256 MB
     * on a device/process that can't comfortably spare it risks an
     * OutOfMemoryError on the encrypt button press instead of gracefully
     * degrading. Still far above the old 64 MB baseline either way.
     */
    private fun chooseArgonMemoryKb(): Int {
        val maxHeapKb = Runtime.getRuntime().maxMemory() / 1024L
        // Require the chosen Argon2 cost to be a clear fraction of the
        // available heap, not close to all of it (other allocations -
        // the plaintext, UI, dictionaries - share the same heap).
        return if (maxHeapKb > ARGON_MEMORY_KB * 4) ARGON_MEMORY_KB else REDUCED_ARGON_MEMORY_KB
    }

    private fun buildHeaderV3(hasExpiry: Boolean, expiryEpochSeconds: Long, memoryKb: Int): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_LENGTH_V3)
        buffer.put(FORMAT_VERSION)
        buffer.put(if (hasExpiry) 1.toByte() else 0.toByte())
        buffer.putLong(expiryEpochSeconds)
        buffer.putInt(memoryKb)
        buffer.put(ARGON_ITERATIONS.toByte())
        buffer.put(ARGON_PARALLELISM.toByte())
        return buffer.array()
    }

    fun encrypt(textChars: CharArray, passChars: CharArray, expirySeconds: Long?): String {
        require(textChars.isNotEmpty()) { "plaintext is empty" }
        require(passChars.isNotEmpty()) { "passphrase is empty" }
        require(expirySeconds == null || expirySeconds in 0L..(365L * 24L * 60L * 60L)) {
            "expiry is out of range"
        }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val memoryKb = chooseArgonMemoryKb()
        val keyBytes = deriveKey(passChars, salt, memoryKb = memoryKb)
        val plainBytes = charsToUtf8Bytes(textChars)
        try {
            val hasExpiry = expirySeconds != null
            val expiryEpoch = if (hasExpiry) (System.currentTimeMillis() / 1000L) + expirySeconds!! else 0L
            val header = buildHeaderV3(hasExpiry, expiryEpoch, memoryKb)

            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val cipherBytes = cipher.doFinal(plainBytes)

            val combined = header + salt + iv + cipherBytes
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            Arrays.fill(keyBytes, 0)
            Arrays.fill(plainBytes, 0)
        }
    }

    /**
     * Returns the decrypted plaintext as a CharArray (never String, so
     * the caller can zero it immediately after use - this is the most
     * sensitive value in the whole app).
     */
    fun decrypt(b64: String, passChars: CharArray): CharArray {
        require(passChars.isNotEmpty()) { "passphrase is empty" }
        val encoded = b64.trim()
        require(encoded.isNotEmpty() && encoded.length <= MAX_CIPHERTEXT_BASE64_CHARS) {
            "ciphertext too large or empty"
        }
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.isNotEmpty()) { "ciphertext too short" }

        val headerLength = when (combined[0]) {
            VERSION_V2 -> HEADER_LENGTH_V2
            VERSION_V3 -> HEADER_LENGTH_V3
            else -> throw IllegalArgumentException("unsupported ciphertext format version")
        }
        require(combined.size > headerLength + SALT_LENGTH + IV_LENGTH) { "ciphertext too short" }

        val header = combined.copyOfRange(0, headerLength)
        val headerBuf = ByteBuffer.wrap(header)
        val version = headerBuf.get()
        val hasExpiry = headerBuf.get().toInt() == 1
        val expiryEpoch = headerBuf.long
        // v2 messages never stored their own Argon2 params - they must be
        // re-derived with the exact constants that were hardcoded when
        // they were encrypted, regardless of today's defaults. v3 messages
        // carry their own params, so raising the default in a future
        // release can never break decrypting an older v3 message either.
        val (memoryKb, iterations, parallelism) = if (version == VERSION_V3) {
            Triple(headerBuf.int, headerBuf.get().toInt(), headerBuf.get().toInt())
        } else {
            Triple(LEGACY_V2_ARGON_MEMORY_KB, LEGACY_V2_ARGON_ITERATIONS, LEGACY_V2_ARGON_PARALLELISM)
        }

        // The v3 header is authenticated by GCM, but it must still be treated
        // as untrusted BEFORE Argon2 allocates memory. Without bounds, an
        // attacker could feed a crafted header containing a huge memory cost
        // and turn decryption into an application-level memory exhaustion/DoS.
        require(memoryKb in MIN_ARGON_MEMORY_KB..MAX_ARGON_MEMORY_KB) {
            "invalid Argon2 memory cost"
        }
        require(iterations in MIN_ARGON_ITERATIONS..MAX_ARGON_ITERATIONS) {
            "invalid Argon2 iteration count"
        }
        require(parallelism in MIN_ARGON_PARALLELISM..MAX_ARGON_PARALLELISM) {
            "invalid Argon2 parallelism"
        }
        require(memoryKb >= parallelism * 8) {
            "invalid Argon2 memory/parallelism combination"
        }

        // Checked BEFORE spending time on the (deliberately expensive)
        // Argon2id key derivation below, so an expired message fails
        // fast without doing the costly work first.
        if (hasExpiry && System.currentTimeMillis() / 1000L > expiryEpoch) {
            throw ExpiredMessageException()
        }

        val salt = combined.copyOfRange(headerLength, headerLength + SALT_LENGTH)
        val iv = combined.copyOfRange(headerLength + SALT_LENGTH, headerLength + SALT_LENGTH + IV_LENGTH)
        val cipherBytes = combined.copyOfRange(headerLength + SALT_LENGTH + IV_LENGTH, combined.size)
        val keyBytes = deriveKey(passChars, salt, memoryKb, iterations, parallelism)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val plainBytes = cipher.doFinal(cipherBytes)
            try {
                val charBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
                val chars = CharArray(charBuffer.remaining())
                charBuffer.get(chars)
                return chars
            } finally {
                Arrays.fill(plainBytes, 0)
            }
        } catch (e: AEADBadTagException) {
            // Wrong key OR a tampered header/ciphertext - GCM
            // deliberately can't tell you which, that's by design.
            throw e
        } finally {
            Arrays.fill(keyBytes, 0)
        }
    }

    /**
     * Cheap, non-cryptographic sanity check used by the keyboard's
     * quick-decrypt panel to decide whether clipboard content is even
     * WORTH attempting to decrypt (so it can say "no encrypted message
     * found" instead of running the semi-expensive Argon2id path on
     * whatever unrelated text happens to be on the clipboard).
     */
    fun looksLikeCiphertext(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 40) return false
        val decoded = try {
            Base64.decode(trimmed, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        return decoded.size > HEADER_LENGTH_V2 + SALT_LENGTH + IV_LENGTH
    }
}
