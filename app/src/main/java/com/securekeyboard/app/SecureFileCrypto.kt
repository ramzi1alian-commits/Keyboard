package com.securekeyboard.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Contact-bound streaming file encryption.
 *
 * The file key is derived from the same ECDH contact identity used by
 * CryptoEngineV2, but with a file-specific HKDF info/salt so message and file
 * keys are domain-separated. The active SessionKeyStore passphrase is an
 * additional secret factor. No network is involved.
 *
 * Format (SKF2 v1): fixed header || encrypted filename || encrypted content.
 * The filename is encrypted, not stored in plaintext. Content is streamed so
 * large videos/PDFs do not need to fit in RAM.
 */
object SecureFileCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    private const val VERSION: Byte = 1
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH = 32
    private const val HEADER_LENGTH = 4 + 1 + IV_LENGTH + IV_LENGTH + 4
    private const val MAX_METADATA_CIPHER = 64 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    private fun deriveKey(
        context: Context,
        contactPublicKey: PublicKey,
        passphrase: CharArray
    ): ByteArray = ContactCrypto.deriveAes256Key(
        context,
        contactPublicKey,
        passphrase,
        ContactCrypto.Purpose.FILE
    )

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
        val key = deriveKey(context, contactPublicKey, passphrase)
        val metaIv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val contentIv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val filename = (displayName ?: "encrypted_file").ifBlank { "encrypted_file" }
        val filenameBytes = filename.toByteArray(Charsets.UTF_8)
        require(filenameBytes.size <= 4096) { "filename too long" }

        val header = ByteBuffer.allocate(HEADER_LENGTH)
            .put(MAGIC)
            .put(VERSION)
            .put(metaIv)
            .put(contentIv)
            .putInt(0) // replaced after metadata ciphertext is known
            .array()
        val metaCipher = try {
            aesGcmEncrypt(key, metaIv, header.copyOf(HEADER_LENGTH - 4), filenameBytes)
        } finally {
            Arrays.fill(filenameBytes, 0)
        }
        require(metaCipher.size <= MAX_METADATA_CIPHER)
        ByteBuffer.wrap(header, HEADER_LENGTH - 4, 4).putInt(metaCipher.size)

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
                            } finally {
                                Arrays.fill(buffer, 0)
                            }
                        }
                    }
                }
            }
        }
        Arrays.fill(key, 0)
        Arrays.fill(metaIv, 0)
        Arrays.fill(contentIv, 0)
        Arrays.fill(header, 0)
        Arrays.fill(metaCipher, 0)
    }

    /** Decrypts to a private temporary file first, then caller may copy it after authentication succeeds. */
    fun decryptToTemp(context: Context, input: Uri, contactPublicKey: PublicKey, passphrase: CharArray): Pair<File, String> {
        require(passphrase.isNotEmpty()) { "passphrase is empty" }
        val key = deriveKey(context, contactPublicKey, passphrase)
        val temp = File.createTempFile("skf_dec_", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(input).use { rawIn ->
                require(rawIn != null) { "cannot open input" }
                BufferedInputStream(rawIn, BUFFER_SIZE).use { inputStream ->
                    val header = ByteArray(HEADER_LENGTH)
                    readFully(inputStream, header)
                    require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a SecureKeyboard file" }
                    require(header[4] == VERSION) { "unsupported file version" }
                    val metaIv = header.copyOfRange(5, 5 + IV_LENGTH)
                    val contentIv = header.copyOfRange(5 + IV_LENGTH, 5 + IV_LENGTH * 2)
                    val metaLen = ByteBuffer.wrap(header, HEADER_LENGTH - 4, 4).int
                    require(metaLen in 16..MAX_METADATA_CIPHER) { "invalid metadata" }
                    val metaCipher = ByteArray(metaLen)
                    readFully(inputStream, metaCipher)
                    val aadHeader = header.copyOf(HEADER_LENGTH - 4)
                    val filenameBytes = aesGcmDecrypt(key, metaIv, aadHeader, metaCipher)
                    val filename = try { String(filenameBytes, Charsets.UTF_8) } finally { Arrays.fill(filenameBytes, 0) }

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, contentIv))
                    cipher.updateAAD(header + metaCipher)
                    CipherInputStream(inputStream, cipher).use { encryptedIn ->
                        FileOutputStream(temp).use { rawOut ->
                            BufferedOutputStream(rawOut, BUFFER_SIZE).use { out ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                try {
                                    while (true) {
                                        val read = encryptedIn.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                    }
                                    out.flush()
                                } finally {
                                    Arrays.fill(buffer, 0)
                                }
                            }
                        }
                    }
                    Arrays.fill(metaIv, 0)
                    Arrays.fill(contentIv, 0)
                    Arrays.fill(metaCipher, 0)
                    Arrays.fill(aadHeader, 0)
                    Arrays.fill(header, 0)
                    return temp to filename
                }
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            Arrays.fill(key, 0)
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            require(n >= 0) { "truncated encrypted file" }
            offset += n
        }
    }
}
