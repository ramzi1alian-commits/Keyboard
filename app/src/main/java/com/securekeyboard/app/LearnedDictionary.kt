package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Personal on-device word-frequency dictionary.
 * Sensitive fields are gated by SecureInputMethodService and never call learn().
 * Disk storage is encrypted with LocalStorageCrypto (Android Keystore + AES-GCM).
 */
object LearnedDictionary {

    private const val FILE_NAME = "learned_words.tsv"
    private const val MAX_ENTRIES = 8000

    private val counts = ConcurrentHashMap<String, Int>()
    private val loadStarted = AtomicBoolean(false)
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "learned-dictionary-persist").apply { isDaemon = true }
    }

    fun preload(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        Thread {
            loadFromDisk(context.applicationContext)
        }.apply { isDaemon = true }.start()
    }

    fun learn(context: Context, word: String) {
        if (word.length < 2) return
        counts.merge(word, 1) { old, inc -> (old + inc).coerceAtMost(Int.MAX_VALUE) }
        persist(context.applicationContext)
    }

    fun suggestionsFor(prefix: String, max: Int = 5): List<String> {
        if (prefix.isEmpty() || counts.isEmpty() || max <= 0) return emptyList()
        return counts.entries.asSequence()
            .filter { it.key.length >= prefix.length && it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key }
            .toList()
    }

    fun clear(context: Context) {
        counts.clear()
        persistExecutor.execute {
            try {
                File(context.applicationContext.filesDir, FILE_NAME).delete()
            } catch (_: Exception) {
                // Best effort; memory is already cleared.
            }
        }
    }

    fun exportText(): String = buildString {
        for ((word, count) in counts) {
            append(word).append('\t').append(count).append('\n')
        }
    }

    fun importText(context: Context, text: String): Int {
        var imported = 0
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val word = line.substring(0, tab)
            val count = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            if (word.isEmpty() || count <= 0) continue
            counts.merge(word, count) { old, added ->
                old.toLong().plus(added.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            imported++
        }
        if (imported > 0) persist(context.applicationContext)
        return imported
    }

    private fun loadFromDisk(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        var encrypted: ByteArray? = null
        var decrypted: ByteArray? = null
        try {
            encrypted = file.readBytes()
            decrypted = LocalStorageCrypto.decrypt(encrypted) ?: return
            BufferedReader(InputStreamReader(decrypted.inputStream(), Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val word = line.substring(0, tab)
                    val count = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    if (word.isNotEmpty() && count > 0) counts[word] = count
                }
            }
        } catch (_: Exception) {
            // Corrupt/unreadable storage never crashes the keyboard.
        } finally {
            encrypted?.fill(0)
            decrypted?.fill(0)
        }
    }

    private fun persist(context: Context) {
        persistExecutor.execute {
            trimIfNeeded()
            var plainBytes: ByteArray? = null
            var encryptedBytes: ByteArray? = null
            try {
                val sb = StringBuilder()
                for ((word, count) in counts) {
                    sb.append(word).append('\t').append(count).append('\n')
                }
                plainBytes = sb.toString().toByteArray(Charsets.UTF_8)
                encryptedBytes = LocalStorageCrypto.encrypt(plainBytes)
                val target = File(context.filesDir, FILE_NAME)
                val temp = File(context.filesDir, "$FILE_NAME.tmp")
                temp.outputStream().use { out ->
                    out.write(encryptedBytes)
                    out.flush()
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw java.io.IOException("atomic dictionary replace failed")
                }
            } catch (_: Exception) {
                // Learning is optional; never break typing.
            } finally {
                plainBytes?.fill(0)
                encryptedBytes?.fill(0)
            }
        }
    }

    private fun trimIfNeeded() {
        if (counts.size <= MAX_ENTRIES) return
        val toRemove = counts.entries
            .sortedBy { it.value }
            .take(counts.size - MAX_ENTRIES)
        for (entry in toRemove) counts.remove(entry.key)
    }
}
