package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * PhraseDictionary
 *
 * A PERSONAL, ON-DEVICE, PER-SENTENCE frequency dictionary that grows
 * from whole lines the user finishes with the إدخال (Enter) key - only
 * in fields the focused app has NOT marked sensitive. Gated by the exact
 * same [suggestionsEnabled] check in SecureInputMethodService that gates
 * LearnedDictionary, so a sensitive field never reaches learn() here
 * either.
 *
 * READ THIS BEFORE ASSUMING IT'S "JUST LIKE LearnedDictionary" - IT IS A
 * BIGGER PRIVACY TRADE-OFF AND SHOULD BE UNDERSTOOD AS SUCH:
 *
 * - LearnedDictionary only ever stores individual WORDS with a typed
 *   count - never in the order typed, never as a sentence, never with
 *   any surrounding context.
 * - This class stores WHOLE LINES OF TEXT, close to verbatim (the line
 *   the user just finished with Enter, in a non-sensitive field), so
 *   that exact sentence can be offered again later as a one-tap
 *   suggestion. That is real, on-device storage of something much
 *   closer to "what you actually wrote" than a word-frequency count is.
 * - Still: this file never leaves the device (the app declares no
 *   android.permission.INTERNET anywhere), it is excluded from Android
 *   backups exactly like learned_words.tsv (see
 *   android:allowBackup="false" / dataExtractionRules), and the user can
 *   wipe it at any time from Settings ("مسح الجمل المحفوظة") - separate
 *   from, and in addition to, clearing the learned-words list.
 * - A line is only ever saved once it has at least two words - a single
 *   word typed and finished with Enter is left to LearnedDictionary, not
 *   duplicated here.
 * - Long lines are capped (see MAX_PHRASE_LENGTH): anything longer than
 *   that is skipped entirely, never truncated-and-saved, so a long
 *   pasted paragraph or message can't quietly end up stored whole.
 * - This is a frequency counter over exact lines, not a timestamped log:
 *   a saved phrase tells you it was typed N times, nothing about when.
 */
object PhraseDictionary {

    private const val FILE_NAME = "learned_phrases.tsv"

    // Sentences take much more room than single words, so the cap here
    // is far lower than LearnedDictionary's MAX_ENTRIES - this is meant
    // to hold a modest set of genuinely-repeated phrases, not a growing
    // transcript.
    private const val MAX_ENTRIES = 300
    private const val MAX_PHRASE_LENGTH = 120

    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var loaded = false

    fun preload(context: Context) {
        if (loaded) return
        Thread {
            loadFromDisk(context.applicationContext)
            loaded = true
        }.apply { isDaemon = true }.start()
    }

    /**
     * Records [line] as a finished phrase, but only if it's a genuine
     * multi-word sentence within the length cap - blank, single-word, or
     * over-length input is silently ignored, so the caller doesn't need
     * to pre-filter before calling this.
     */
    fun learn(context: Context, line: String) {
        val phrase = line.trim()
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE_LENGTH) return
        val wordCount = phrase.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (wordCount < 2) return
        counts.merge(phrase, 1) { old, inc -> old + inc }
        persist(context.applicationContext)
    }

    /**
     * Up to [max] previously-saved phrases whose FIRST word starts with
     * [prefix], most-used first - so a phrase can be offered as a
     * one-tap suggestion as soon as the user starts typing its opening
     * word again.
     */
    fun suggestionsFor(prefix: String, max: Int = 2): List<String> {
        if (prefix.isEmpty()) return emptyList()
        if (counts.isEmpty()) return emptyList()
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

    /** Wipes all saved phrases, in memory and on disk, immediately. */
    fun clear(context: Context) {
        counts.clear()
        Thread {
            try {
                File(context.applicationContext.filesDir, FILE_NAME).delete()
            } catch (_: Exception) {
                // Worst case it's stale/empty and gets overwritten by
                // the next persist() anyway.
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * SECURITY FIX: this file used to be written as plain UTF-8 text -
     * see LocalStorageCrypto's doc. It is now encrypted at rest with a
     * Keystore-backed key; the TSV format itself is unchanged.
     */
    private fun loadFromDisk(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            val decrypted = LocalStorageCrypto.decrypt(file.readBytes()) ?: return
            BufferedReader(InputStreamReader(decrypted.inputStream(), Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val phrase = line.substring(0, tab)
                    val count = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    if (phrase.isNotEmpty()) counts[phrase] = count
                }
            }
        } catch (_: Exception) {
            // Corrupt/unreadable file - start fresh rather than crash
            // the keyboard over a non-essential feature.
        }
    }

    private fun persist(context: Context) {
        Thread {
            trimIfNeeded()
            try {
                context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
                    val sb = StringBuilder()
                    for ((phrase, count) in counts) {
                        sb.append(phrase).append('\t').append(count).append('\n')
                    }
                    out.write(LocalStorageCrypto.encrypt(sb.toString().toByteArray(Charsets.UTF_8)))
                }
            } catch (_: Exception) {
                // Learning is a nice-to-have, not core functionality - a
                // failed save here should never crash typing.
            }
        }.apply { isDaemon = true }.start()
    }

    private fun trimIfNeeded() {
        if (counts.size <= MAX_ENTRIES) return
        val toRemove = counts.entries
            .sortedBy { it.value }
            .take(counts.size - MAX_ENTRIES)
        for (entry in toRemove) {
            counts.remove(entry.key)
        }
    }
}
