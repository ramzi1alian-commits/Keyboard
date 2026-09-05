package com.securekeyboard.app

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Contact-bound streaming file encryption.
 *
 * SKF2 v2 uses a fresh P-256 ephemeral key pair for every encrypted file.
 * The sender encrypts with ECDHE(ephemeralPrivate, recipientStaticPublic);
 * the receiver derives the same key with ECDHE(recipientStaticPrivate,
 * senderEphemeralPublic). The ephemeral public key is authenticated as part
 * of the GCM header. SKF2 v1 remains decryptable for migration compatibility.
 */
object SecureFileCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    private const val VERSION_LEGACY: Byte = 1
    private const val VERSION_ECDHE: Byte = 2
    private const val IV_LENGTH = 12
    private const val EPHEMERAL_MAX = 200
    private const val LEGACY_HEADER_LENGTH = 4 + 1 + IV_LENGTH + IV_LENGTH + 4
    private const val ECDHE_FIXED_HEADER = 4 + 1 + 2 + IV_LENGTH + IV_LENGTH + 4
    private const val MAX_METADATA_CIPHER = 64 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherBytes)
    }

    fun encrypt(context: Context, input: Uri, output: Uri, contactPublicKey: PublicKey, passphrase: CharArray, displayName: String?) {
        require(passphrase.isNotEmpty()) { "passphrase is empty" }
        val ephemeral = DeviceIdentity.generateEphemeralKeyPair()
        val key = ContactCrypto.deriveAes256KeyFromEphemeralPrivate(
            ephemeral.private, contactPublicKey, passphrase, ContactCrypto.Purpose.FILE
        )
        val metaIv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val contentIv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        var header = ByteArray(0)
        var metaCipher = ByteArray(0)
        var ephemeralPub = ephemeral.public.encoded
        try {
            require(ephemeralPub.size in 50..EPHEMERAL_MAX) { "invalid ephemeral public key" }
            val filenameBytes = (displayName ?: "encrypted_file").ifBlank { "encrypted_file" }.toByteArray(Charsets.UTF_8)
            try {
                require(filenameBytes.size <= 4096) { "filename too long" }
                val fixed = ByteBuffer.allocate(ECDHE_FIXED_HEADER)
                    .put(MAGIC).put(VERSION_ECDHE).putShort(ephemeralPub.size.toShort())
                    .put(metaIv).put(contentIv).putInt(0).array()
                header = MAGIC + byteArrayOf(VERSION_ECDHE) +
                    ByteBuffer.allocate(2).putShort(ephemeralPub.size.toShort()).array() +
                    ephemeralPub + metaIv + contentIv + ByteArray(4)
                metaCipher = aesGcmEncrypt(key, metaIv, header.copyOf(header.size - 4), filenameBytes)
                require(metaCipher.size <= MAX_METADATA_CIPHER)
                ByteBuffer.wrap(header, header.size - 4, 4).putInt(metaCipher.size)
                Arrays.fill(fixed, 0)
            } finally { Arrays.fill(filenameBytes, 0) }

            context.contentResolver.openInputStream(input).use { rawIn ->
                require(rawIn != null) { "cannot open input" }
                context.contentResolver.openOutputStream(output).use { rawOut ->
                    require(rawOut != null) { "cannot open output" }
                    BufferedInputStream(rawIn, BUFFER_SIZE).use { inputStream ->
                        BufferedOutputStream(rawOut, BUFFER_SIZE).use { outputStream ->
                            outputStream.write(header)
                            outputStream.write(metaCipher)
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, contentIv))
                            cipher.updateAAD(header + metaCipher)
                            CipherOutputStream(outputStream, cipher).use { encryptedOut ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                try {
                                    while (true) {
                                        val read = inputStream.read(buffer)
                                        if (read < 0) break
                                        encryptedOut.write(buffer, 0, read)
                                    }
                                } finally { Arrays.fill(buffer, 0) }
                            }
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(key, 0); Arrays.fill(metaIv, 0); Arrays.fill(contentIv, 0)
            Arrays.fill(header, 0); Arrays.fill(metaCipher, 0); Arrays.fill(ephemeralPub, 0)
        }
    }

    fun decryptToTemp(context: Context, input: Uri, contactPublicKey: PublicKey, passphrase: CharArray): Pair<File, String> {
        require(passphrase.isNotEmpty()) { "passphrase is empty" }
        val temp = File.createTempFile("skf_dec_", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(input).use { rawIn ->
                require(rawIn != null) { "cannot open input" }
                BufferedInputStream(rawIn, BUFFER_SIZE).use { inputStream ->
                    val prefix = ByteArray(5)
                    try {
                        readFully(inputStream, prefix)
                        require(prefix.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a SecureKeyboard file" }
                        return when (prefix[4]) {
                            VERSION_LEGACY -> decryptLegacy(context, inputStream, prefix, contactPublicKey, passphrase, temp)
                            VERSION_ECDHE -> decryptEcdhe(context, inputStream, prefix, passphrase, temp)
                            else -> throw IllegalArgumentException("unsupported file version")
                        }
                    } finally { Arrays.fill(prefix, 0) }
                }
            }
        } catch (e: Exception) {
            SecureMemory.secureDelete(temp)
            throw e
        }
    }

    private fun decryptEcdhe(context: Context, input: java.io.InputStream, prefix: ByteArray, passphrase: CharArray, temp: File): Pair<File, String> {
        val rest = ByteArray(ECDHE_FIXED_HEADER - 5)
        readFully(input, rest)
        val ephLen = ByteBuffer.wrap(rest, 0, 2).short.toInt() and 0xffff
        require(ephLen in 50..EPHEMERAL_MAX) { "invalid ephemeral key" }
        val ephemeralBytes = ByteArray(ephLen)
        readFully(input, ephemeralBytes)
        val tail = rest.copyOfRange(2, rest.size)
        val metaLen = ByteBuffer.wrap(tail, tail.size - 4, 4).int
        require(metaLen in 16..MAX_METADATA_CIPHER) { "invalid metadata" }
        val header = prefix + rest + ephemeralBytes
        val metaIv = tail.copyOfRange(0, IV_LENGTH)
        val contentIv = tail.copyOfRange(IV_LENGTH, IV_LENGTH * 2)
        val metaCipher = ByteArray(metaLen)
        var key = ByteArray(0)
        try {
            readFully(input, metaCipher)
            val ephemeralPublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephemeralBytes))
            key = ContactCrypto.deriveAes256KeyFromEphemeralPublic(context, ephemeralPublic, passphrase, ContactCrypto.Purpose.FILE)
            val filenameBytes = aesGcmDecrypt(key, metaIv, header.copyOf(header.size - 4), metaCipher)
            val filename = try { String(filenameBytes, Charsets.UTF_8) } finally { Arrays.fill(filenameBytes, 0) }
            decryptContent(input, temp, key, contentIv, header, metaCipher)
            return temp to filename
        } finally {
            Arrays.fill(key, 0)
            Arrays.fill(rest, 0); Arrays.fill(ephemeralBytes, 0); Arrays.fill(tail, 0)
            Arrays.fill(metaIv, 0); Arrays.fill(contentIv, 0); Arrays.fill(metaCipher, 0); Arrays.fill(header, 0)
        }
    }

    private fun decryptContent(input: java.io.InputStream, temp: File, key: ByteArray, contentIv: ByteArray, header: ByteArray, metaCipher: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, contentIv))
        cipher.updateAAD(header + metaCipher)
        FileOutputStream(temp).use { rawOut ->
            BufferedOutputStream(rawOut, BUFFER_SIZE).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        val plain = cipher.update(buffer, 0, n)
                        if (plain != null && plain.isNotEmpty()) { out.write(plain); Arrays.fill(plain, 0) }
                    }
                    val finalPlain = cipher.doFinal()
                    if (finalPlain.isNotEmpty()) { out.write(finalPlain); Arrays.fill(finalPlain, 0) }
                    out.flush()
                } finally { Arrays.fill(buffer, 0) }
            }
        }
    }

    private fun decryptLegacy(context: Context, input: java.io.InputStream, prefix: ByteArray, contactPublicKey: PublicKey, passphrase: CharArray, temp: File): Pair<File, String> {
        val rest = ByteArray(LEGACY_HEADER_LENGTH - 5)
        val header = prefix + rest
        val metaIv = ByteArray(IV_LENGTH)
        val contentIv = ByteArray(IV_LENGTH)
        val metaCipher: ByteArray
        var key = ByteArray(0)
        try {
            readFully(input, rest)
            System.arraycopy(header, 5, metaIv, 0, IV_LENGTH)
            System.arraycopy(header, 5 + IV_LENGTH, contentIv, 0, IV_LENGTH)
            val metaLen = ByteBuffer.wrap(header, LEGACY_HEADER_LENGTH - 4, 4).int
            require(metaLen in 16..MAX_METADATA_CIPHER) { "invalid metadata" }
            metaCipher = ByteArray(metaLen)
            readFully(input, metaCipher)
            key = ContactCrypto.deriveAes256Key(context, contactPublicKey, passphrase, ContactCrypto.Purpose.FILE)
            val filenameBytes = aesGcmDecrypt(key, metaIv, header.copyOf(LEGACY_HEADER_LENGTH - 4), metaCipher)
            val filename = try { String(filenameBytes, Charsets.UTF_8) } finally { Arrays.fill(filenameBytes, 0) }
            decryptContent(input, temp, key, contentIv, header, metaCipher)
            Arrays.fill(metaCipher, 0)
            return temp to filename
        } finally {
            Arrays.fill(key, 0); Arrays.fill(rest, 0); Arrays.fill(header, 0); Arrays.fill(metaIv, 0); Arrays.fill(contentIv, 0)
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray){var offset=0;while(offset<buffer.size){val n=input.read(buffer,offset,buffer.size-offset);require(n>=0){"truncated encrypted file"};offset+=n}}
}
