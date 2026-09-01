package com.securekeyboard.app

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * WordDictionary
 *
 * Loads a fixed, bundled, offline word list (assets/ar_words.tsv - shipped
 * inside the APK itself, not downloaded) and serves prefix-based
 * suggestions while the user types.
 *
 * PRIVACY NOTE - how this fits the app's existing guarantees:
 * - The dictionary is READ-ONLY. It ships inside the app and is never
 *   written to, appended to, or modified based on anything the user
 *   types. There is no "learning" - a word you type a thousand times is
 *   ranked in the suggestion list exactly the same as the first time.
 * - Nothing the user types is added to this list, saved to disk, or
 *   sent anywhere (this app still has no INTERNET permission at all).
 * - The only thing kept in memory is the current in-progress word being
 *   typed (see SecureInputMethodService.currentWord), which is a plain
 *   in-memory StringBuilder cleared on space/enter/field switch - not a
 *   history, and never written to storage.
 *
 * Source data: CAMeL Arabic Frequency Lists (Classical Arabic variety) as
 * a base, merged with word-frequency counts from a public Arabic news
 * corpus (~75K additional Arabic-script word types), so contemporary news
 * vocabulary is covered alongside the classical base list. Words present
 * in both sources get a small confidence boost. Raw counts are rescaled
 * to a 1-255 popularity score (same convention Android's own AOSP
 * dictionaries use).
 */
object WordDictionary {

    private const val ASSET_NAME = "ar_words.tsv"
    private const val MAX_SUGGESTIONS = 5

    // Bucketed by first character so a per-keystroke lookup only has to
    // scan words that could possibly match, instead of all ~40,000 every
    // time. Each bucket's list stays sorted by descending frequency
    // score (the source file is already sorted that way, and grouping
    // preserves relative order).
    @Volatile
    private var buckets: Map<Char, List<Pair<String, Int>>>? = null

    @Volatile
    private var loading = false

    /**
     * Kicks off a background load the first time it's needed (e.g. when
     * the keyboard view is first created). Safe to call repeatedly - it
     * only actually loads once per process.
     */
    fun preload(context: Context) {
        if (buckets != null || loading) return
        loading = true
        Thread {
            val result = loadFromAssets(context.applicationContext)
            buckets = result
            loading = false
        }.apply { isDaemon = true }.start()
    }

    private fun loadFromAssets(context: Context): Map<Char, List<Pair<String, Int>>> {
        val grouped = LinkedHashMap<Char, MutableList<Pair<String, Int>>>()
        try {
            context.assets.open(ASSET_NAME).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEachLine
                        val word = line.substring(0, tab)
                        val freq = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                        if (word.isEmpty()) return@forEachLine
                        val first = word[0]
                        grouped.getOrPut(first) { mutableListOf() }.add(word to freq)
                    }
                }
            }
        } catch (e: Exception) {
            // Fail closed and quiet: if the bundled asset is ever missing
            // or malformed, suggestions simply stay empty. The keyboard
            // itself (typing letters directly) never depends on this.
            return emptyMap()
        }
        return grouped
    }

    /**
     * Returns up to [MAX_SUGGESTIONS] words starting with [prefix],
     * ranked by frequency score. Returns an empty list while the
     * dictionary is still loading in the background, or if [prefix] is
     * blank - this never blocks the typing thread waiting for disk I/O.
     */
    fun suggestionsFor(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val map = buckets ?: return emptyList()
        val bucket = map[prefix[0]] ?: return emptyList()
        if (bucket.isEmpty()) return emptyList()
        val out = ArrayList<String>(MAX_SUGGESTIONS)
        for ((word, _) in bucket) {
            if (word.length >= prefix.length && word.startsWith(prefix)) {
                out.add(word)
                if (out.size >= MAX_SUGGESTIONS) break
            }
        }
        return out
    }
}
