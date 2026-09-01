package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * LearnedDictionary
 *
 * A PERSONAL, ON-DEVICE, PER-WORD frequency dictionary that grows from
 * words the user actually types - but ONLY in fields the focused app has
 * NOT marked as sensitive (see suggestionsEnabled in
 * SecureInputMethodService, which is what actually decides whether
 * learn() is ever called - this class itself does no field-type
 * checking, the caller does).
 *
 * SCOPE OF WHAT THIS CHANGES ABOUT THE APP'S PRIVACY MODEL - READ THIS
 * BEFORE ASSUMING THE OLD "NOTHING TYPED IS EVER STORED" CLAIM STILL
 * APPLIES EVERYWHERE:
 *
 * - This app is used as the SYSTEM KEYBOARD, so it can be the active
 *   input method for ANY other app on the device (messaging, notes,
 *   browsers, banking apps, everywhere) - not just this app's own
 *   screens. When this keyboard is active in one of those other apps
 *   (as long as that app hasn't marked the field sensitive), individual
 *   WORDS typed there are now saved to a local file on this device
 *   (see FILE_NAME) so they can be suggested again later, with more
 *   frequently-typed words ranked higher. That is new, real, on-device
 *   storage of (parts of) what was typed - it did not exist before this
 *   change and is a genuine trade-off, not a cosmetic one.
 * - This file NEVER leaves the device: the app still declares no
 *   android.permission.INTERNET anywhere, so there is no code path that
 *   could transmit it even if it tried to.
 * - This file is EXCLUDED from Android backups, same as the rest of the
 *   app's data (see android:allowBackup="false" / dataExtractionRules in
 *   the manifest) - it does not sync to a Google account or another
 *   device.
 * - Only individual WORDS are stored (as "word -> how many times
 *   typed"), never full messages, never with any context of which app
 *   or field they came from, and never in the order they were typed.
 * - This is a frequency counter, not a transcript: seeing "بيت: 4" tells
 *   you that word was typed 4 times, nothing about when, where, or in
 *   what sentence.
 * - The user can wipe this file at any time from Settings ("مسح الكلمات
 *   المتعلمة" - see SettingsActivity), which deletes it immediately and
 *   starts learning fresh.
 * - The user can also EXPORT this file to a text file of their own
 *   choosing (via Android's file picker, never automatically) and
 *   IMPORT it back later - e.g. to carry it to a new device, since
 *   normal Android backups are excluded by design. This is a manual,
 *   user-initiated action every time; nothing here is uploaded, synced,
 *   or shared with this app's knowledge or involvement.
 * - Fields the focused app marks sensitive - a password field, or any
 *   field with the TYPE_TEXT_FLAG_NO_SUGGESTIONS flag, which includes
 *   THIS APP'S OWN EncryptActivity message/key fields - never reach
 *   learn() at all (enforced in SecureInputMethodService, not here), so
 *   nothing typed there is ever added to this file, exactly as before.
 */
object LearnedDictionary {

    private const val FILE_NAME = "learned_words.tsv"

    // Hard cap on distinct learned words, so an extremely heavy typist
    // over months/years can't grow this file without bound. When
    // exceeded, the least-frequently-typed words are dropped first -
    // they're the ones least useful to keep suggesting anyway.
    private const val MAX_ENTRIES = 8000

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
     * Records that [word] was just typed-and-completed (space/enter/tapped
     * suggestion) in a field that is NOT marked sensitive. The caller
     * (SecureInputMethodService) is responsible for that check - this
     * function itself has no way to know what field it came from, so it
     * trusts the caller's gate.
     */
    fun learn(context: Context, word: String) {
        if (word.length < 2) return
        counts.merge(word, 1) { old, inc -> old + inc }
        persist(context.applicationContext)
    }

    /** Up to [max] previously-learned words starting with [prefix], most-typed first. */
    fun suggestionsFor(prefix: String, max: Int = 5): List<String> {
        if (prefix.isEmpty()) return emptyList()
        if (counts.isEmpty()) return emptyList()
        return counts.entries.asSequence()
            .filter { it.key.length >= prefix.length && it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key }
            .toList()
    }

    /** Wipes all learned words, in memory and on disk, immediately. */
    fun clear(context: Context) {
        counts.clear()
        Thread {
            try {
                File(context.applicationContext.filesDir, FILE_NAME).delete()
            } catch (_: Exception) {
                // Nothing to do - worst case the file is empty/stale and
                // gets overwritten on the next persist() anyway.
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * MANUAL export/import (see SettingsActivity's "نسخ احتياطي للقاموس"
     * card) - the user explicitly picks a destination/source file via
     * Android's own file picker (Storage Access Framework), so this is
     * still never automatic and never touches the network (no INTERNET
     * permission anywhere in the app either way). This is the intended
     * way to carry a learned vocabulary to a new device or restore it
     * after reinstalling, since backups are otherwise excluded by design
     * (android:allowBackup="false").
     */
    fun exportText(): String {
        val sb = StringBuilder()
        for ((word, count) in counts) {
            sb.append(word).append('\t').append(count).append('\n')
        }
        return sb.toString()
    }

    /**
     * Merges [text] (in the same word\tcount\n format exportText()
     * produces) into the current in-memory dictionary and persists the
     * result. Matching words have their counts ADDED together (so
     * re-importing the same file twice roughly doubles those counts,
     * same trade-off as tapping a suggestion twice - not treated
     * specially here since this is a rare, user-initiated action, not
     * something that happens silently). Malformed lines are skipped, not
     * fatal. Returns how many word entries were merged in, so the caller
     * can show a meaningful confirmation.
     */
    fun importText(context: Context, text: String): Int {
        var imported = 0
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val word = line.substring(0, tab)
            val count = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            if (word.isEmpty()) continue
            counts.merge(word, count) { old, added -> old + added }
            imported++
        }
        if (imported > 0) {
            persist(context.applicationContext)
        }
        return imported
    }

    /**
     * SECURITY FIX: this file used to be written as plain UTF-8 text.
     * It is now encrypted at rest via LocalStorageCrypto (Android
     * Keystore-backed AES-256-GCM key, never exportable from the
     * Keystore) - see that class's doc for why. Reading/writing the
     * TSV format itself (word\tcount\n) is unchanged; only the bytes
     * that hit disk are now ciphertext instead of plaintext.
     */
    private fun loadFromDisk(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            val decrypted = LocalStorageCrypto.decrypt(file.readBytes())
            if (decrypted == null) {
                // Either corrupt, or a leftover plaintext file from
                // before this fix - either way, don't try to parse
                // ciphertext/garbage as TSV. Start fresh; the file gets
                // rewritten (encrypted) on the next persist().
                return
            }
            BufferedReader(InputStreamReader(decrypted.inputStream(), Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val word = line.substring(0, tab)
                    val count = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    if (word.isNotEmpty()) counts[word] = count
                }
            }
        } catch (_: Exception) {
            // Corrupt/unreadable file - start fresh rather than crash the
            // keyboard over a non-essential feature.
        }
    }

    /**
     * Rewrites the whole file from the current in-memory map, on a
     * background thread. Simple full-rewrite-per-learned-word is fine at
     * this dictionary's expected size (a personal vocabulary, capped at
     * MAX_ENTRIES) - this is not the large static ar_words.tsv asset.
     */
    private fun persist(context: Context) {
        Thread {
            trimIfNeeded()
            try {
                context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
                    val sb = StringBuilder()
                    for ((word, count) in counts) {
                        sb.append(word).append('\t').append(count).append('\n')
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
