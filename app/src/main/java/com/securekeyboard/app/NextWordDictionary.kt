package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * NextWordDictionary
 *
 * Loads a fixed, bundled, offline word-PAIR list (assets/ar_bigrams.tsv -
 * shipped inside the APK itself, not downloaded) built from a general
 * Arabic news corpus's neighbor co-occurrence statistics: which word
 * tends to follow which. This is what lets the suggestion bar offer
 * likely NEXT words the instant a space is typed, before the user has
 * typed anything of the next word at all - the same "smart" behavior as
 * WordDictionary's prefix suggestions, just keyed on the word just
 * finished instead of a partial prefix.
 *
 * PRIVACY NOTE - identical guarantee to WordDictionary:
 * - READ-ONLY, bundled in the APK, never written to or modified based on
 *   anything the user types. Nothing here "learns" from this device.
 * - Source data: co-occurrence statistics derived from a public Arabic
 *   news corpus (general text, not personal to any user), filtered to
 *   Arabic-script word pairs and rescaled to a small 1-99 popularity
 *   score. This file never leaves the device (no INTERNET permission).
 */
object NextWordDictionary {

    private const val ASSET_NAME = "ar_bigrams.tsv"
    private const val MAX_SUGGESTIONS = 3

    @Volatile
    private var table: Map<String, List<String>>? = null

    @Volatile
    private var loading = false

    fun preload(context: Context) {
        if (table != null || loading) return
        loading = true
        Thread {
            table = loadFromAssets(context.applicationContext)
            loading = false
        }.apply { isDaemon = true }.start()
    }

    private fun loadFromAssets(context: Context): Map<String, List<String>> {
        val grouped = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        try {
            context.assets.open(ASSET_NAME).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.split('\t')
                        if (parts.size != 3) return@forEachLine
                        val w1 = parts[0]
                        val w2 = parts[1]
                        val score = parts[2].toIntOrNull() ?: return@forEachLine
                        if (w1.isEmpty() || w2.isEmpty()) return@forEachLine
                        grouped.getOrPut(w1) { mutableListOf() }.add(w2 to score)
                    }
                }
            }
        } catch (e: Exception) {
            // Fail closed and quiet, same convention as WordDictionary:
            // a missing/malformed asset just means no next-word chips,
            // never a crash.
            return emptyMap()
        }
        // Source file is already sorted per-word by descending score, but
        // resort defensively in case that ever changes.
        return grouped.mapValues { (_, pairs) ->
            pairs.sortedByDescending { it.second }.map { it.first }
        }
    }

    /**
     * Returns up to [MAX_SUGGESTIONS] words that commonly follow
     * [previousWord] in general Arabic text, most-likely first. Returns
     * an empty list while still loading, or if [previousWord] is blank
     * or has no known followers - never blocks the typing thread.
     */
    fun suggestionsFor(previousWord: String): List<String> {
        if (previousWord.isEmpty()) return emptyList()
        val map = table ?: return emptyList()
        val followers = map[previousWord] ?: return emptyList()
        return if (followers.size <= MAX_SUGGESTIONS) followers else followers.take(MAX_SUGGESTIONS)
    }
}
