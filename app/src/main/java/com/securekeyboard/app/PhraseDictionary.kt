package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Personal on-device phrase-frequency dictionary.
 * Sensitive fields are gated by SecureInputMethodService and never call learn().
 * Disk storage is encrypted with LocalStorageCrypto (Android Keystore + AES-GCM).
 */
object PhraseDictionary {

    private const val FILE_NAME = "learned_phrases.tsv"
    private const val MAX_ENTRIES = 300
    private const val MAX_PHRASE_LENGTH = 120

    private val counts = ConcurrentHashMap<String, Int>()
    private val loadStarted = AtomicBoolean(false)
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phrase-dictionary-persist").apply { isDaemon = true }
    }

    fun preload(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        Thread {
            loadFromDisk(context.applicationContext)
        }.apply { isDaemon = true }.start()
    }

    fun learn(context: Context, line: String) {
        val phrase = line.trim()
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE_LENGTH) return
        var words = 0
        var insideWord = false
        for (c in phrase) {
            if (c.isWhitespace()) {
                insideWord = false
            } else if (!insideWord) {
                words++
                insideWord = true
            }
        }
        if (words < 2) return
        counts.merge(phrase, 1) { old, inc -> (old + inc).coerceAtMost(Int.MAX_VALUE) }
        persist(context.applicationContext)
    }

    /**
     * Returns the continuation of saved phrases after the supplied context.
     * Example: saved "السلام عليكم ورحمة الله وبركاته" + context
     * "السلام عليكم" -> "ورحمة الله وبركاته".
     */
    fun suggestionsForContext(context: String, max: Int = 2): List<String> {
        val normalized = context.trim()
        if (normalized.isEmpty() || counts.isEmpty() || max <= 0) return emptyList()
        val prefix = "$normalized "
        return counts.entries.asSequence()
            .filter { entry -> entry.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key.substring(prefix.length).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun suggestionsFor(prefix: String, max: Int = 2): List<String> {
        if (prefix.isEmpty() || counts.isEmpty() || max <= 0) return emptyList()
        return counts.entries.asSequence()
            .filter { entry ->
                val firstWord = entry.key.substringBefore(' ')
                firstWord.length >= prefix.length && firstWord.startsWith(prefix)
            }
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
                    val phrase = line.substring(0, tab)
                    val count = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    if (phrase.isNotEmpty() && count > 0) counts[phrase] = count
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
                for ((phrase, count) in counts) {
                    sb.append(phrase).append('\t').append(count).append('\n')
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
