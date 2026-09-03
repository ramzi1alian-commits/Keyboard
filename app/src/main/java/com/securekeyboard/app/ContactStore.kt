package com.securekeyboard.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ContactStore
 *
 * Persists (contact name -> public key) pairs that have completed
 * ContactPairingActivity's Safety Number confirmation. Stored via the
 * same LocalStorageCrypto used elsewhere in this app (encrypted at
 * rest with a key held in Android Keystore) - so the list of who
 * you've paired with isn't sitting in a plaintext file even locally.
 *
 * This file never touches the network (consistent with the rest of
 * the app - no INTERNET permission exists in the manifest, so nothing
 * here even could sync anywhere).
 */
object ContactStore {

    private const val STORE_FILENAME = "paired_contacts.enc"

    private fun storeFile(context: Context): File =
        File(context.filesDir, STORE_FILENAME)

    private fun loadAll(context: Context): JSONObject {
        val file = storeFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            val encryptedBytes = file.readBytes()
            // LocalStorageCrypto.decrypt returns null (never throws) on any
            // failure - corrupt file, pre-upgrade format, invalidated key -
            // so a null here means "treat as empty", not "crash".
            val plaintext = LocalStorageCrypto.decrypt(encryptedBytes) ?: return JSONObject()
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            // Corrupt or unreadable store - fail safe to empty rather than
            // crash; the user will simply need to re-pair contacts.
            JSONObject()
        }
    }

    private fun saveAll(context: Context, data: JSONObject) {
        val plaintext = data.toString().toByteArray(Charsets.UTF_8)
        val encryptedBytes = LocalStorageCrypto.encrypt(plaintext)
        storeFile(context).writeBytes(encryptedBytes)
    }

    /** Called once ContactPairingActivity's Safety Number step is confirmed. */
    @Synchronized
    fun savePairedContact(context: Context, name: String, publicKeyBase64: String) {
        val all = loadAll(context)
        all.put(name, publicKeyBase64)
        saveAll(context, all)
    }

    @Synchronized
    fun getPairedContact(context: Context, name: String): String? {
        return loadAll(context).optString(name).takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun listPairedContactNames(context: Context): List<String> {
        val all = loadAll(context)
        return all.keys().asSequence().toList().sorted()
    }

    @Synchronized
    fun removePairedContact(context: Context, name: String) {
        val all = loadAll(context)
        all.remove(name)
        saveAll(context, all)
    }
}
