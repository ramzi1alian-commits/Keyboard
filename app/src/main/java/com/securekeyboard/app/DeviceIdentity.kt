package com.securekeyboard.app

import android.content.Context
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.KeyAgreement

/**
 * V14 cross-version device identity.
 *
 * The ECDH identity is deliberately a software EC key pair on API 24-34,
 * encrypted at rest with LocalStorageCrypto. This sacrifices the stronger
 * non-exportable Keystore-private-key property on API 31+, but avoids an
 * Android 12-14 OEM/provider incompatibility where a Keystore EC private key
 * cannot be consumed reliably by the same ECDH path used with imported
 * contact public keys.
 *
 * LocalStorageCrypto still uses an Android Keystore AES key to protect the
 * identity file at rest. A V14 installation therefore has one deterministic
 * ECDH implementation on Android 8 through 14 instead of two incompatible
 * implementations.
 *
 * IMPORTANT: V14 uses a new identity file. Existing paired contacts must be
 * re-paired once after upgrading from V13 because a new device public key is
 * required for a symmetric ECDH relationship.
 */
object DeviceIdentity {
    private const val CURVE = "secp256r1"
    private const val IDENTITY_FILE = "device_identity_v14.enc"
    private const val MAX_KEY_BYTES = 4096

    @Synchronized
    private fun getOrCreateKeyPair(context: Context): KeyPair {
        val file = File(context.filesDir, IDENTITY_FILE)
        if (file.exists()) {
            try {
                val encrypted = file.readBytes()
                val plain = LocalStorageCrypto.decrypt(encrypted)
                if (plain != null) {
                    try {
                        if (plain.size >= 12) {
                            val buf = ByteBuffer.wrap(plain)
                            val publicLen = buf.int
                            if (publicLen in 1..MAX_KEY_BYTES && buf.remaining() >= publicLen + 4) {
                                val publicBytes = ByteArray(publicLen)
                                buf.get(publicBytes)
                                val privateLen = buf.int
                                if (privateLen in 1..MAX_KEY_BYTES && buf.remaining() >= privateLen) {
                                    val privateBytes = ByteArray(privateLen)
                                    buf.get(privateBytes)
                                    try {
                                        val pub = KeyFactory.getInstance("EC")
                                            .generatePublic(X509EncodedKeySpec(publicBytes))
                                        val priv = KeyFactory.getInstance("EC")
                                            .generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                                        return KeyPair(pub, priv)
                                    } finally {
                                        Arrays.fill(publicBytes, 0)
                                        Arrays.fill(privateBytes, 0)
                                    }
                                }
                            }
                        }
                    } finally {
                        Arrays.fill(plain, 0)
                    }
                }
            } catch (_: Exception) {
                // Regenerate a clean identity below.
            }
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val pair = generator.generateKeyPair()
        val pub = pair.public.encoded
        val priv = pair.private.encoded
        val plain = ByteBuffer.allocate(4 + pub.size + 4 + priv.size)
            .putInt(pub.size).put(pub)
            .putInt(priv.size).put(priv)
            .array()
        try {
            file.writeBytes(LocalStorageCrypto.encrypt(plain))
        } finally {
            Arrays.fill(plain, 0)
        }
        return pair
    }

    fun myPublicKeyBase64(context: Context): String =
        Base64.encodeToString(getOrCreateKeyPair(context).public.encoded, Base64.NO_WRAP)

    fun parseContactPublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    fun computeSharedSecret(context: Context, contactPublicKey: PublicKey): ByteArray {
        val pair = getOrCreateKeyPair(context)
        val agreement = try {
            KeyAgreement.getInstance("ECDH")
        } catch (e: Exception) {
            throw IllegalStateException("ECDH is not supported on this Android device", e)
        }
        try {
            agreement.init(pair.private)
            agreement.doPhase(contactPublicKey, true)
            return agreement.generateSecret()
        } catch (e: Exception) {
            throw IllegalStateException("ECDH key agreement failed", e)
        }
    }

    fun isSupported(): Boolean = true
    fun isHardwareBackedPath(): Boolean = false
}
