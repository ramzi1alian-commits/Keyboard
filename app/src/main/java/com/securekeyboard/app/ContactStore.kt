package com.securekeyboard.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Arrays

/**
 * Encrypted local contact store. The encrypted blob is written atomically and
 * all temporary plaintext/ciphertext byte arrays are wiped after use.
 */
object ContactStore {
    private const val STORE_FILENAME = "paired_contacts.enc"
    private const val TEMP_FILENAME = "paired_contacts.enc.tmp"

    private fun storeFile(context: Context): File = File(context.filesDir, STORE_FILENAME)

    private fun loadAll(context: Context): JSONObject {
        val file = storeFile(context)
        if (!file.exists()) return JSONObject()
        var encrypted: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            encrypted = file.readBytes()
            plaintext = LocalStorageCrypto.decrypt(encrypted) ?: return JSONObject()
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        } finally {
            encrypted?.let { Arrays.fill(it, 0) }
            plaintext?.let { Arrays.fill(it, 0) }
        }
    }

    private fun saveAll(context: Context, data: JSONObject) {
        var plaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            plaintext = data.toString().toByteArray(Charsets.UTF_8)
            encrypted = LocalStorageCrypto.encrypt(plaintext)
            val target = storeFile(context)
            val temp = File(context.filesDir, TEMP_FILENAME)
            temp.outputStream().use { out ->
                out.write(encrypted)
                out.flush()
                try { out.fd.sync() } catch (_: Exception) { /* best effort */ }
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw java.io.IOException("atomic contact store replace failed")
            }
        } finally {
            plaintext?.let { Arrays.fill(it, 0) }
            encrypted?.let { Arrays.fill(it, 0) }
        }
    }

    @Synchronized
    fun savePairedContact(context: Context, name: String, publicKeyBase64: String) {
        require(name.isNotBlank() && name.length <= 120) { "invalid contact name" }
        require(publicKeyBase64.isNotBlank() && publicKeyBase64.length <= 4096) { "invalid public key" }
        val all = loadAll(context)
        all.put(name, publicKeyBase64)
        saveAll(context, all)
    }

    @Synchronized
    fun getPairedContact(context: Context, name: String): String? =
        loadAll(context).optString(name).takeIf { it.isNotEmpty() }

    @Synchronized
    fun listPairedContactNames(context: Context): List<String> =
        loadAll(context).keys().asSequence().toList().sorted()

    @Synchronized
    fun removePairedContact(context: Context, name: String) {
        val all = loadAll(context)
        all.remove(name)
        saveAll(context, all)
    }
}
