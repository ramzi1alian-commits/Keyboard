package com.securekeyboard.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * LocalStorageCrypto
 *
 * SECURITY FIX: LearnedDictionary.kt and PhraseDictionary.kt used to
 * write their files (learned_words.tsv / learned_phrases.tsv) as plain
 * UTF-8 text under this app's private files directory. That directory
 * is off-limits to every OTHER normal app on the device (standard
 * Android sandboxing), but it was still plain, readable text to anyone
 * with root access or physical-extraction tooling against the device -
 * i.e. exactly the threat model this app's own encryption screen is
 * built to defend against for the text a user deliberately encrypts.
 * Leaving the keyboard's own learned-word/phrase files unencrypted was
 * an inconsistency, not a deliberate design choice.
 *
 * This object closes that gap: both files are now encrypted with AES-256-
 * GCM using a key that lives ONLY inside the Android Keystore (hardware-
 * backed / StrongBox-backed on devices that support it) and is never
 * exported, never held in this app's own memory as raw key material, and
 * never touches the passphrase/CryptoEngine path at all. Rooting a device
 * does not, by itself, hand over Keystore-protected key material the way
 * it hands over a plain file - on StrongBox/TEE-backed devices the key
 * operations happen in a separate secure environment entirely.
 *
 * This is UNRELATED to the user's own encryption passphrase - messages
 * a user deliberately encrypts to share still go through CryptoEngine's
 * Argon2id+passphrase path exactly as before. This key only protects the
 * keyboard's own local learning files, which the user never sees or
 * types a passphrase for.
 *
 * No user-authentication gate (biometric/PIN) is attached to this key on
 * purpose - these are background typing-suggestion files, not the user's
 * secret message content, so typing suggestions should keep working
 * across a locked/unlocked screen exactly as before this change.
 */
object LocalStorageCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "secure_keyboard_local_storage_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_LENGTH = 12

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Encrypts [plainBytes]; returns iv || ciphertext, ready to write to disk as-is. */
    fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        return iv + cipherBytes
    }

    /**
     * Reverses [encrypt]. Returns null (never throws) on ANY failure -
     * corrupt/truncated file, a pre-upgrade plaintext file left over from
     * before this fix, or a Keystore key that became invalid (e.g. after
     * the user removed their screen lock, which can invalidate some key
     * types on some OEMs) - so callers can fall back to "start fresh"
     * instead of crashing keyboard input over a non-essential feature.
     */
    fun decrypt(combined: ByteArray): ByteArray? {
        if (combined.size <= GCM_IV_LENGTH) return null
        return try {
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            null
        }
    }
}
