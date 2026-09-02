package com.securekeyboard.app

/**
 * Autocorrect
 *
 * OPT-IN (default OFF - see Prefs.autocorrectEnabled / Settings), and
 * deliberately dumb on purpose: a small, FIXED, bundled list of common
 * Arabic and English typos mapped to their correction. This is NOT a
 * spell-checker and NOT a model of any kind - it never learns from what
 * the user types, never grows, and never touches anything outside this
 * hardcoded table. Only kept to words with an unambiguous "this spelling
 * is basically never intentional" correction, so it can't quietly
 * mangle a word the user actually meant to type.
 */
object Autocorrect {

    private val ARABIC_FIXES = mapOf(
        "هاذا" to "هذا",
        "هاذه" to "هذه",
        "هاذي" to "هذه",
        "لاكن" to "لكن",
        "لاكنه" to "لكنه",
        "انشاءالله" to "إن شاء الله",
        "انشاء الله" to "إن شاء الله",
        "ماشاءالله" to "ما شاء الله",
        "استغفرالله" to "أستغفر الله",
        "بسمالله" to "بسم الله",
        "جزاكالله" to "جزاك الله",
        "احسنت" to "أحسنت",
        "اسف" to "آسف",
        "اسفة" to "آسفة",
        "امتى" to "متى",
        "علشان" to "لأن",
        "عايز" to "أريد",
        "بديت" to "بدأت",
        "ابدا" to "أبدا"
    )

    private val ENGLISH_FIXES = mapOf(
        "teh" to "the",
        "adn" to "and",
        "recieve" to "receive",
        "recieved" to "received",
        "seperate" to "separate",
        "definately" to "definitely",
        "occured" to "occurred",
        "wich" to "which",
        "thier" to "their",
        "alot" to "a lot",
        "beleive" to "believe",
        "untill" to "until",
        "wat" to "what"
    )

    /**
     * Returns the correction for [word] if it's a known fixed typo, or
     * null if [word] isn't in the table (the overwhelmingly common case -
     * nothing happens to any word not explicitly listed above).
     */
    fun correct(word: String): String? {
        if (word.isEmpty()) return null
        ARABIC_FIXES[word]?.let { return it }
        val lower = word.lowercase()
        ENGLISH_FIXES[lower]?.let { fixed ->
            // Preserve a capitalized first letter if the user had one
            // (e.g. "Teh" at the start of a sentence -> "The").
            return if (word[0].isUpperCase()) fixed.replaceFirstChar { it.uppercase() } else fixed
        }
        return null
    }
}
