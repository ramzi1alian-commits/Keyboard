package com.securekeyboard.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * DeviceIdentity
 *
 * Generates and holds THIS device's long-term key pair for message
 * exchange, backed by Android Keystore (hardware-backed on most modern
 * devices - the private key material never leaves the secure element
 * and cannot be exported, even by this app's own code, even with root).
 *
 * This is what makes decryption device-bound: two different phones
 * running the exact same APK end up with two DIFFERENT, unrelated
 * private keys, generated independently and never leaving their own
 * Keystore. Knowing the passphrase/visible key is no longer sufficient
 * on its own - see CryptoEngineV2.kt for how this plugs into encryption.
 *
 * Requires API 31+ (Android 12) for Keystore-backed ECDH key agreement.
 * On older devices, fall back to CryptoEngine's existing passphrase-only
 * scheme (see chooseEncryptionMode() at the call site).
 */
object DeviceIdentity {

    private const val KEYSTORE_ALIAS = "securekeyboard_device_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CURVE = "secp256r1" // NIST P-256

    /**
     * Returns this device's key pair, generating it once on first call
     * and reusing the same Keystore-resident key on every call after
     * that (KeyStore.load + getEntry finds the existing alias instead
     * of regenerating - regenerating would orphan any messages already
     * exchanged under the old public key).
     */
    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_AGREE_KEY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // Private key material is non-exportable by construction -
                // there is no API call, on this device, that returns the
                // raw private key bytes to this app's process.
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as java.security.PrivateKey
        val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    /**
     * This device's public key, safe to share with a contact (e.g. as a
     * QR code or a short string pasted once during initial pairing).
     * There is nothing sensitive in this value - sharing it is the
     * whole point, exactly like sharing a phone number.
     */
    fun myPublicKeyBase64(): String {
        val pub = getOrCreateKeyPair().public
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    /** Parses a contact's public key from the string they shared with you. */
    fun parseContactPublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC).generatePublic(spec)
    }

    /**
     * Performs ECDH INSIDE the Keystore - this device's private key
     * never enters this app's process memory as raw bytes at any point.
     * Only the resulting shared secret comes back, which is itself not
     * yet a usable AES key (see CryptoEngineV2.deriveMessageKey, which
     * runs it through HKDF).
     */
    fun computeSharedSecret(contactPublicKey: PublicKey): ByteArray {
        val myPrivateKey = getOrCreateKeyPair().private
        val keyAgreement = KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE)
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(contactPublicKey, true)
        return keyAgreement.generateSecret()
    }

    fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 31
}
