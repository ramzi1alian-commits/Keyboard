package com.securekeyboard.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * DeviceIdentity
 *
 * API 31+ uses an EC key pair protected by Android Keystore and ECDH is
 * performed through the Keystore provider.
 *
 * Android 7-11 do not reliably expose Keystore-backed ECDH. On those
 * versions we use a per-install EC key pair encrypted at rest with the
 * app's Keystore-backed LocalStorageCrypto key. This keeps the pairing
 * feature functional on older Android versions without ever storing the
 * private key as plaintext on disk. The API 31+ path remains the stronger,
 * Keystore-resident implementation.
 */
object DeviceIdentity {

    private const val KEYSTORE_ALIAS = "securekeyboard_device_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CURVE = "secp256r1"
    private const val LEGACY_FILE = "device_identity_v1.enc"

    private fun getKeystoreKeyPair(): KeyPair {
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
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    private fun getLegacyKeyPair(context: Context): KeyPair {
        val file = File(context.filesDir, LEGACY_FILE)
        val encrypted = if (file.exists()) file.readBytes() else null
        if (encrypted != null) {
            val plain = LocalStorageCrypto.decrypt(encrypted)
            if (plain != null && plain.size >= 8) {
                try {
                    val publicLen = java.nio.ByteBuffer.wrap(plain, 0, 4).int
                    if (publicLen > 0 && publicLen < plain.size - 4) {
                        val privateLenOffset = 4 + publicLen
                        val privateLen = java.nio.ByteBuffer.wrap(plain, privateLenOffset, 4).int
                        val privateOffset = privateLenOffset + 4
                        if (privateLen > 0 && privateOffset + privateLen <= plain.size) {
                            val pubBytes = plain.copyOfRange(4, privateLenOffset)
                            val privBytes = plain.copyOfRange(privateOffset, privateOffset + privateLen)
                            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
                            val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
                            plain.fill(0)
                            return KeyPair(pub, priv)
                        }
                    }
                } catch (_: Exception) {
                    // Corrupt/incompatible legacy identity: generate a new one.
                } finally {
                    plain.fill(0)
                }
            }
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val pair = generator.generateKeyPair()
        val pub = pair.public.encoded
        val priv = pair.private.encoded
        val out = java.nio.ByteBuffer.allocate(4 + pub.size + 4 + priv.size)
            .putInt(pub.size).put(pub).putInt(priv.size).put(priv).array()
        file.writeBytes(LocalStorageCrypto.encrypt(out))
        out.fill(0)
        return pair
    }

    private fun getOrCreateKeyPair(context: Context): KeyPair =
        if (Build.VERSION.SDK_INT >= 31) getKeystoreKeyPair() else getLegacyKeyPair(context)

    fun myPublicKeyBase64(context: Context): String =
        Base64.encodeToString(getOrCreateKeyPair(context).public.encoded, Base64.NO_WRAP)

    fun parseContactPublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun computeSharedSecret(context: Context, contactPublicKey: PublicKey): ByteArray {
        val pair = getOrCreateKeyPair(context)
        val agreement = if (Build.VERSION.SDK_INT >= 31) {
            KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE)
        } else {
            KeyAgreement.getInstance("ECDH")
        }
        agreement.init(pair.private)
        agreement.doPhase(contactPublicKey, true)
        return agreement.generateSecret()
    }

    fun isSupported(): Boolean = true
    fun isHardwareBackedPath(): Boolean = Build.VERSION.SDK_INT >= 31
}
