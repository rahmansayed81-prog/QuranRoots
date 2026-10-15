package com.quranroots.app.data.db

/**
 * Search-normalization for Arabic word lookup. MUST stay identical to the
 * Python `normalize_search` in data-prep/build_db.py so a typed word matches
 * the pre-built `word_forms` index: strip combining marks / tatweel and fold
 * alif/hamza/ya/ta-marbuta variants to a common base.
 *
 * Users type imlaa'i spelling (no dagger-alif, no waw/ya-written long-a), so
 * the query only needs the base variant; the index already stores the extra
 * rasm variants.
 */
/**
 * Normalization for *root* queries. The DB stores roots with every hamza form
 * folded to ا (امن، سال، قرا), so «أمن» / «سأل» / «ق ر أ» must fold the same
 * way. Keeps Arabic letters only (drops spaces, diacritics, tatweel).
 */
fun normalizeRoot(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
        val folded = when (ch) {
            'ء', 'أ', 'إ', 'آ', 'ؤ', 'ئ', 'ٱ' -> 'ا'
            'ى' -> 'ي'
            'ة' -> 'ه'
            else -> ch
        }
        if (folded.code in 0x0627..0x064A && folded.code !in 0x063B..0x0640) sb.append(folded)
    }
    return sb.toString()
}

fun normalizeSearch(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
        val cp = ch.code
        if (cp in 0x0610..0x061A ||
            cp in 0x064B..0x065F ||
            cp == 0x0670 ||
            cp in 0x06D6..0x06ED ||
            cp == 0x0640
        ) continue
        sb.append(
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                'ة' -> 'ه'
                else -> ch
            }
        )
    }
    return sb.toString().trim()
}
