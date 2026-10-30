package com.quranroots.app.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.quranroots.app.data.db.entities.AyahEntity
import com.quranroots.app.data.db.entities.ExternalRootInfo
import com.quranroots.app.data.db.entities.Hadith
import com.quranroots.app.data.db.entities.HadithHits
import com.quranroots.app.data.db.entities.LemmaMorph
import com.quranroots.app.data.db.entities.WaznEntry
import com.quranroots.app.data.db.entities.WaznWord
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.data.db.entities.LetterRoot
import com.quranroots.app.data.db.entities.OppositeLink
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.SurahEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.db.entities.WordMatch
import java.io.File
import java.io.FileOutputStream

/**
 * Read-only access to the prebuilt Quran SQLite database shipped in
 * `assets/quran-roots.db`.
 *
 * We deliberately bypass Room for this DB because Room verifies a generated
 * schema identity hash that we cannot predict from a Python-built file.
 * Schema is fixed and read-only, so plain SQLite is appropriate.
 */
class QuranDataSource(context: Context) {

    private val db: SQLiteDatabase? = openDb(context)

    val isReady: Boolean get() = db != null

    private fun openDb(context: Context): SQLiteDatabase? {
        // Versioned cached filename: bumping DB_VERSION forces a re-copy from
        // assets on next launch. Old versioned copies are deleted to reclaim
        // space.
        val target = File(context.filesDir, "$DB_NAME.v$DB_VERSION")
        if (!target.exists()) {
            // Clean up stale versions.
            context.filesDir.listFiles { f ->
                f.name.startsWith("$DB_NAME") && f.absolutePath != target.absolutePath
            }?.forEach { runCatching { it.delete() } }
            try {
                context.assets.open(DB_NAME).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            } catch (_: Throwable) {
                return null
            }
        }
        return try {
            SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (_: SQLiteException) {
            null
        }
    }

    private fun <T> safeQuery(default: T, block: (SQLiteDatabase) -> T): T =
        db?.let(block) ?: default

    fun rootCount(): Int = safeQuery(0) { d ->
        d.rawQuery("SELECT COUNT(*) FROM roots", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun rootByLetters(letters: String): RootEntity? = safeQuery(null) { d ->
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots WHERE letters = ? LIMIT 1",
            arrayOf(letters)
        ).use { c -> if (c.moveToFirst()) cursorToRoot(c) else null }
    }

    fun rootById(id: Long): RootEntity? = safeQuery(null) { d ->
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots WHERE root_id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) cursorToRoot(c) else null }
    }

    fun rootsByIds(ids: Collection<Long>): List<RootEntity> {
        if (ids.isEmpty()) return emptyList()
        return safeQuery(emptyList()) { d ->
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            d.rawQuery(
                "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                    "FROM roots WHERE root_id IN ($placeholders)",
                args
            ).use { c ->
                val out = mutableListOf<RootEntity>()
                while (c.moveToNext()) out += cursorToRoot(c)
                out
            }
        }
    }

    fun searchPrefix(prefix: String, limit: Int = 50): List<RootEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots " +
                "WHERE letters LIKE ? OR letters_spaced LIKE ? " +
                "ORDER BY occurrences DESC LIMIT ?",
            arrayOf("$prefix%", "$prefix%", limit.toString())
        ).use { c ->
            val out = mutableListOf<RootEntity>()
            while (c.moveToNext()) out += cursorToRoot(c)
            out
        }
    }

    /** All roots ordered by the given column. `orderBy` is validated against a
     *  whitelist to keep the raw query injection-safe. */
    fun allRoots(orderBy: String): List<RootEntity> = safeQuery(emptyList()) { d ->
        val order = when (orderBy) {
            "occurrences" -> "occurrences DESC"
            "forms" -> "forms_count DESC, occurrences DESC"
            "letters" -> "letters ASC"
            else -> "occurrences DESC"
        }
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots ORDER BY $order",
            null
        ).use { c ->
            val out = mutableListOf<RootEntity>()
            while (c.moveToNext()) out += cursorToRoot(c)
            out
        }
    }

    fun topRoots(limit: Int = 30): List<RootEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots ORDER BY occurrences DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val out = mutableListOf<RootEntity>()
            while (c.moveToNext()) out += cursorToRoot(c)
            out
        }
    }

    fun relatedRoots(rootId: Long, limit: Int = 12): List<RootEntity> =
        joinedRootsFrom("root_relations", rootId, limit)

    fun semanticRoots(rootId: Long, limit: Int = 12): List<RootEntity> =
        joinedRootsFrom("root_semantic", rootId, limit)

    fun morphoRoots(rootId: Long, limit: Int = 12): List<RootEntity> =
        joinedRootsFrom("root_morpho", rootId, limit)

    // --- خ-22 / خ-23: Sahihayn hadith -------------------------------------

    /** (hadith count, word count) for a root; (0, 0) if the table is absent. */
    fun hadithStats(rootId: Long): Pair<Int, Int> = safeQuery(0 to 0) { d ->
        runCatching {
            d.rawQuery(
                "SELECT COUNT(DISTINCT hid), COUNT(*) FROM hadith_root_words WHERE root_id = ?",
                arrayOf(rootId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }
        }.getOrDefault(0 to 0)
    }

    /** hid → word numbers of `rootId` in that hadith, with each hid's unit (خ-26). */
    fun hadithHitsForRoot(rootId: Long): HadithHits = safeQuery(HadithHits.EMPTY) { d ->
        runCatching {
            d.rawQuery(
                "SELECT w.hid, w.word_no, h.unit_id, h.is_rep, h.track FROM hadith_root_words w " +
                    "JOIN hadith h ON h.hid = w.hid WHERE w.root_id = ?",
                arrayOf(rootId.toString())
            ).use { c ->
                val words = HashMap<Long, MutableSet<Int>>()
                val units = HashMap<Long, Long>()
                val reps = HashSet<Long>()
                val outside = HashSet<Long>()
                while (c.moveToNext()) {
                    val hid = c.getLong(0)
                    words.getOrPut(hid) { HashSet() } += c.getInt(1)
                    units[hid] = c.getLong(2)
                    if (c.getInt(3) == 1) reps += hid
                    if (c.getString(4) != "sahihayn") outside += hid
                }
                HadithHits(words, units, reps, outside)
            }
        }.getOrDefault(HadithHits.EMPTY)
    }

    fun hadiths(hids: List<Long>): List<Hadith> {
        if (hids.isEmpty()) return emptyList()
        return safeQuery(emptyList()) { d ->
            val ph = hids.joinToString(",") { "?" }
            d.rawQuery(
                "SELECT hid, book, hadith_no, chapter, section, isnad, tokens, unit_id, is_rep, other_narrations, track, url " +
                    "FROM hadith WHERE hid IN ($ph)",
                hids.map { it.toString() }.toTypedArray()
            ).use { c ->
                val byId = HashMap<Long, Hadith>()
                while (c.moveToNext()) {
                    val h = Hadith(
                        hid = c.getLong(0), book = c.getString(1),
                        hadithNo = c.getString(2) ?: "",
                        chapter = if (c.isNull(3)) null else c.getString(3),
                        section = if (c.isNull(4)) null else c.getString(4),
                        isnad = if (c.isNull(5)) null else c.getString(5),
                        words = c.getString(6).split(' '),
                        unitId = c.getLong(7),
                        isRep = c.getInt(8) == 1,
                        otherNarrations = c.getInt(9),
                        track = c.getString(10),
                        url = if (c.isNull(11)) null else c.getString(11)
                    )
                    byId[h.hid] = h
                }
                hids.mapNotNull { byId[it] }   // keep the caller's order
            }
        }
    }

    // --- خ-40: التصريف والميزان -------------------------------------------

    /** A word's التصريف reduced to its lemma's: a verb keeps only its باب
     *  (فعل — ثلاثي مجرد), a noun drops number and gender. */
    private fun lemmaLabel(label: String): String {
        val parts = label.split(" — ")
        return if (parts.first().startsWith("فعل")) "فعل — " + parts.last()
        else parts.filterNot { it.startsWith("جمع") || it == "مثنى" || it == "مؤنث" }.joinToString(" — ")
    }

    /** Each lemma of a root with its most frequent lemma ميزان and label. */
    fun lemmaMorphForRoot(rootId: Long): List<LemmaMorph> = safeQuery(emptyList()) { d ->
        runCatching {
            d.rawQuery(
                "SELECT COALESCE(w.lemma, w.text), z.id, z.text, l.text, COUNT(*) AS n " +
                    "FROM words w JOIN word_morph m USING (surah_no, ayah_no, word_no) " +
                    "LEFT JOIN wazn z ON z.id = m.lemma_wazn_id " +
                    "LEFT JOIN morph_label l ON l.id = m.label_id " +
                    "WHERE w.root_id = ? GROUP BY 1, 2, 4 ORDER BY n DESC",
                arrayOf(rootId.toString())
            ).use { c ->
                val seen = LinkedHashMap<String, LemmaMorph>()
                while (c.moveToNext()) {
                    val lemma = c.getString(0)
                    if (lemma in seen) continue          // most frequent analysis wins
                    val wz = if (c.isNull(1)) null else WaznEntry(c.getLong(1), c.getString(2), true)
                    seen[lemma] = LemmaMorph(lemma, wz, if (c.isNull(3)) null else lemmaLabel(c.getString(3)))
                }
                seen.values.toList()
            }
        }.getOrDefault(emptyList())
    }

    private val waznCounts =
        "SELECT z.id, z.text, z.is_lemma, z.n_words, z.n_roots FROM wazn z WHERE %s ORDER BY z.n_words DESC"

    private fun collectWazn(c: Cursor): List<WaznEntry> {
        val out = mutableListOf<WaznEntry>()
        while (c.moveToNext()) {
            out += WaznEntry(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getInt(3), c.getInt(4))
        }
        return out
    }

    /** ميزان entries whose letters match `plain` (diacritics ignored), most used first. */
    fun searchWazn(plain: String): List<WaznEntry> = safeQuery(emptyList()) { d ->
        runCatching {
            d.rawQuery(waznCounts.format("z.plain = ?"), arrayOf(plain)).use(::collectWazn)
        }.getOrDefault(emptyList())
    }

    /** Every ميزان of one kind (lemma or word), most used first — for browsing. */
    fun waznCatalog(isLemma: Boolean): List<WaznEntry> = safeQuery(emptyList()) { d ->
        runCatching {
            d.rawQuery(waznCounts.format("z.is_lemma = ?"), arrayOf(if (isLemma) "1" else "0"))
                .use(::collectWazn)
        }.getOrDefault(emptyList())
    }

    fun waznById(id: Long): WaznEntry? = safeQuery(null) { d ->
        runCatching {
            d.rawQuery(waznCounts.format("z.id = ?"), arrayOf(id.toString())).use(::collectWazn).firstOrNull()
        }.getOrNull()
    }

    /** Other ميزان entries spelled with the same letters (فَعَلَ، فَعْل، فِعْل …). */
    fun waznSiblings(id: Long): List<WaznEntry> = safeQuery(emptyList()) { d ->
        runCatching {
            val where = "z.plain = (SELECT plain FROM wazn WHERE id = ?) AND z.id != ?"
            d.rawQuery(waznCounts.format(where), arrayOf(id.toString(), id.toString())).use(::collectWazn)
        }.getOrDefault(emptyList())
    }

    /** Quran words on ميزان `id`, in mushaf order. */
    fun waznWords(wazn: WaznEntry): List<WaznWord> = safeQuery(emptyList()) { d ->
        runCatching {
            val col = if (wazn.isLemma) "m.lemma_wazn_id" else "m.wazn_id"
            d.rawQuery(
                "SELECT w.surah_no, w.ayah_no, w.word_no, w.text, w.lemma, l.text, " +
                    "r.root_id, r.letters, r.letters_spaced, r.occurrences, r.forms_count, r.meaning_ar " +
                    "FROM word_morph m JOIN words w USING (surah_no, ayah_no, word_no) " +
                    "JOIN roots r ON r.root_id = w.root_id " +
                    "LEFT JOIN morph_label l ON l.id = m.label_id " +
                    "WHERE $col = ? ORDER BY w.surah_no, w.ayah_no, w.word_no",
                arrayOf(wazn.id.toString())
            ).use { c ->
                val out = mutableListOf<WaznWord>()
                while (c.moveToNext()) {
                    out += WaznWord(
                        c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3),
                        if (c.isNull(4)) null else c.getString(4),
                        RootEntity(c.getLong(6), c.getString(7), c.getString(8), c.getInt(9), c.getInt(10),
                            if (c.isNull(11)) null else c.getString(11)),
                        if (c.isNull(5)) null else c.getString(5)
                    )
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    fun rootGloss(rootId: Long): String? = safeQuery(null) { d ->
        d.rawQuery("SELECT gloss FROM root_glosses WHERE root_id = ?", arrayOf(rootId.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** Meaning of a non-Quranic Arabic root (Wiktionary), if known. */
    fun externalGloss(letters: String): String? = safeQuery(null) { d ->
        d.rawQuery("SELECT gloss FROM external_roots WHERE letters = ?", arrayOf(letters))
            .use { c -> if (c.moveToFirst()) c.getString(0).ifBlank { null } else null }
    }

    /** Distinct (surah, ayah) keys where a root occurs. */
    fun ayahKeysForRoot(rootId: Long): Set<Pair<Int, Int>> = safeQuery(emptySet()) { d ->
        d.rawQuery(
            "SELECT DISTINCT surah_no, ayah_no FROM words WHERE root_id = ?",
            arrayOf(rootId.toString())
        ).use { c ->
            val out = HashSet<Pair<Int, Int>>()
            while (c.moveToNext()) out += c.getInt(0) to c.getInt(1)
            out
        }
    }

    /** التقابل: curated pairs first, then automatic, each by shared ayahs. */
    fun oppositeRoots(rootId: Long): List<OppositeLink> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT r.root_id, r.letters, r.letters_spaced, r.occurrences, r.forms_count, r.meaning_ar, " +
                "o.source, o.cooccur, o.label " +
                "FROM root_opposites o INNER JOIN roots r ON r.root_id = o.opposite_root_id " +
                "WHERE o.root_id = ? " +
                "ORDER BY (o.source = 'curated') DESC, o.cooccur DESC",
            arrayOf(rootId.toString())
        ).use { c ->
            val out = mutableListOf<OppositeLink>()
            while (c.moveToNext()) {
                out += OppositeLink(cursorToRoot(c), c.getString(6), c.getInt(7), c.getString(8))
            }
            out
        }
    }

    private fun joinedRootsFrom(table: String, rootId: Long, limit: Int): List<RootEntity> =
        safeQuery(emptyList()) { d ->
            d.rawQuery(
                "SELECT r.root_id, r.letters, r.letters_spaced, r.occurrences, r.forms_count, r.meaning_ar " +
                    "FROM roots r INNER JOIN $table rel ON rel.related_root_id = r.root_id " +
                    "WHERE rel.root_id = ? ORDER BY rel.score DESC LIMIT ?",
                arrayOf(rootId.toString(), limit.toString())
            ).use { c ->
                val out = mutableListOf<RootEntity>()
                while (c.moveToNext()) out += cursorToRoot(c)
                out
            }
        }

    fun ayah(surah: Int, ayah: Int): AyahEntity? = safeQuery(null) { d ->
        d.rawQuery(
            "SELECT surah_no, ayah_no, text_uthmani, text_simple " +
                "FROM ayahs WHERE surah_no = ? AND ayah_no = ? LIMIT 1",
            arrayOf(surah.toString(), ayah.toString())
        ).use { c -> if (c.moveToFirst()) cursorToAyah(c) else null }
    }

    fun surah(surah: Int): SurahEntity? = safeQuery(null) { d ->
        d.rawQuery(
            "SELECT surah_no, name_ar, name_translit, ayah_count " +
                "FROM surahs WHERE surah_no = ? LIMIT 1",
            arrayOf(surah.toString())
        ).use { c ->
            if (c.moveToFirst())
                SurahEntity(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3))
            else null
        }
    }

    fun wordsForRoot(rootId: Long): List<WordEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT surah_no, ayah_no, word_no, text, lemma, root_id FROM words " +
                "WHERE root_id = ? ORDER BY surah_no, ayah_no, word_no",
            arrayOf(rootId.toString())
        ).use { collectWords(it) }
    }

    fun ayahsForRoot(rootId: Long): List<AyahEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT DISTINCT a.surah_no, a.ayah_no, a.text_uthmani, a.text_simple " +
                "FROM ayahs a INNER JOIN words w " +
                "ON w.surah_no = a.surah_no AND w.ayah_no = a.ayah_no " +
                "WHERE w.root_id = ? ORDER BY a.surah_no, a.ayah_no",
            arrayOf(rootId.toString())
        ).use { c ->
            val out = mutableListOf<AyahEntity>()
            while (c.moveToNext()) out += cursorToAyah(c)
            out
        }
    }

    fun wordsForAyah(surah: Int, ayah: Int): List<WordEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT surah_no, ayah_no, word_no, text, lemma, root_id FROM words " +
                "WHERE surah_no = ? AND ayah_no = ? ORDER BY word_no",
            arrayOf(surah.toString(), ayah.toString())
        ).use { collectWords(it) }
    }

    private fun collectWords(c: Cursor): List<WordEntity> {
        val out = mutableListOf<WordEntity>()
        while (c.moveToNext()) {
            out += WordEntity(
                surahNo = c.getInt(0),
                ayahNo = c.getInt(1),
                wordNo = c.getInt(2),
                text = c.getString(3),
                lemma = if (c.isNull(4)) null else c.getString(4),
                rootId = if (c.isNull(5)) null else c.getLong(5)
            )
        }
        return out
    }

    /** Quranic roots whose letters are exactly one of `candidates`. */
    fun rootsWithLetters(candidates: Collection<String>): List<RootEntity> {
        if (candidates.isEmpty()) return emptyList()
        return safeQuery(emptyList()) { d ->
            val ph = candidates.joinToString(",") { "?" }
            d.rawQuery(
                "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                    "FROM roots WHERE letters IN ($ph) ORDER BY occurrences DESC",
                candidates.toTypedArray()
            ).use { c ->
                val out = mutableListOf<RootEntity>()
                while (c.moveToNext()) out += cursorToRoot(c)
                out
            }
        }
    }

    /** A non-Quranic root's meaning + nearest Quranic roots, if we know it. */
    fun externalRoot(letters: String): ExternalRootInfo? = safeQuery(null) { d ->
        val gloss = d.rawQuery(
            "SELECT gloss FROM external_roots WHERE letters = ? LIMIT 1", arrayOf(letters)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@safeQuery null
        val syns = d.rawQuery(
            "SELECT r.root_id, r.letters, r.letters_spaced, r.occurrences, r.forms_count, r.meaning_ar " +
                "FROM external_synonyms s INNER JOIN roots r ON r.root_id = s.root_id " +
                "WHERE s.ext_letters = ? ORDER BY s.score DESC",
            arrayOf(letters)
        ).use { c ->
            val out = mutableListOf<RootEntity>()
            while (c.moveToNext()) out += cursorToRoot(c)
            out
        }
        ExternalRootInfo(letters, gloss, syns)
    }

    /** Search by a (possibly diacritized) word → the root(s) it belongs to.
     *  Tries an exact normalized match first, then a prefix, then contains.
     *  Results are grouped by root and ordered by backing occurrence count. */
    fun searchWord(query: String, limit: Int = 40): List<WordMatch> {
        val norm = normalizeSearch(query)
        if (norm.isEmpty()) return emptyList()
        // Exact, then prefix, then contains — stop at the first non-empty tier.
        val exact = wordFormsWhere("wf.form_plain = ?", arrayOf(norm), limit)
        if (exact.isNotEmpty()) return exact
        val prefix = wordFormsWhere("wf.form_plain LIKE ?", arrayOf("$norm%"), limit)
        if (prefix.isNotEmpty()) return prefix
        return wordFormsWhere("wf.form_plain LIKE ?", arrayOf("%$norm%"), limit)
    }

    private fun wordFormsWhere(where: String, args: Array<String>, limit: Int): List<WordMatch> =
        safeQuery(emptyList()) { d ->
            // Aggregate per root: sum counts, pick the most-backed sample form.
            d.rawQuery(
                "SELECT r.root_id, r.letters, r.letters_spaced, r.occurrences, " +
                    "r.forms_count, r.meaning_ar, " +
                    "(SELECT wf2.sample FROM word_forms wf2 WHERE wf2.root_id = r.root_id " +
                    " AND $where ORDER BY wf2.count DESC LIMIT 1) AS sample, " +
                    "SUM(wf.count) AS total " +
                    "FROM word_forms wf INNER JOIN roots r ON r.root_id = wf.root_id " +
                    "WHERE $where " +
                    "GROUP BY r.root_id ORDER BY total DESC LIMIT ?",
                args + args + arrayOf(limit.toString())
            ).use { c ->
                val out = mutableListOf<WordMatch>()
                while (c.moveToNext()) {
                    out += WordMatch(
                        root = cursorToRoot(c),
                        sample = if (c.isNull(6)) "" else c.getString(6),
                        count = c.getInt(7)
                    )
                }
                out
            }
        }

    // --- Letters feature ----------------------------------------------------

    fun allLetters(): List<LetterEntity> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT letter, hija_order, abjad_order, abjad_value, freq_simple, " +
                "freq_uthmani, root_count, weighted_occ, is_muqattaa, makhraj_group " +
                "FROM letters ORDER BY hija_order",
            null
        ).use { c ->
            val out = mutableListOf<LetterEntity>()
            while (c.moveToNext()) out += cursorToLetter(c)
            out
        }
    }

    fun letter(ch: String): LetterEntity? = safeQuery(null) { d ->
        d.rawQuery(
            "SELECT letter, hija_order, abjad_order, abjad_value, freq_simple, " +
                "freq_uthmani, root_count, weighted_occ, is_muqattaa, makhraj_group " +
                "FROM letters WHERE letter = ? LIMIT 1",
            arrayOf(ch)
        ).use { c -> if (c.moveToFirst()) cursorToLetter(c) else null }
    }

    /** All roots containing `ch`, each tagged with the letter's first position
     *  within the root. Sorted by root frequency desc; caller may re-sort. */
    fun rootsForLetter(ch: String): List<LetterRoot> = safeQuery(emptyList()) { d ->
        d.rawQuery(
            "SELECT root_id, letters, letters_spaced, occurrences, forms_count, meaning_ar " +
                "FROM roots WHERE letters LIKE ? ORDER BY occurrences DESC",
            arrayOf("%$ch%")
        ).use { c ->
            val out = mutableListOf<LetterRoot>()
            while (c.moveToNext()) {
                val root = cursorToRoot(c)
                val pos = root.letters.indexOf(ch.first())
                if (pos >= 0) out += LetterRoot(root, pos)
            }
            out
        }
    }

    /** Roots co-occurring letter pairs, for the "letter repulsion" analysis:
     *  returns the set of letters that appear together with `ch` in ≥1 root. */
    fun lettersCoOccurringWith(ch: String): Set<Char> = safeQuery(emptySet()) { d ->
        d.rawQuery(
            "SELECT letters FROM roots WHERE letters LIKE ?",
            arrayOf("%$ch%")
        ).use { c ->
            val set = mutableSetOf<Char>()
            while (c.moveToNext()) c.getString(0).forEach { set += it }
            set - ch.first()
        }
    }

    private fun cursorToLetter(c: Cursor): LetterEntity = LetterEntity(
        letter = c.getString(0),
        hijaOrder = c.getInt(1),
        abjadOrder = c.getInt(2),
        abjadValue = c.getInt(3),
        freqSimple = c.getInt(4),
        freqUthmani = c.getInt(5),
        rootCount = c.getInt(6),
        weightedOcc = c.getInt(7),
        isMuqattaa = c.getInt(8) != 0,
        makhrajGroup = c.getString(9)
    )

    private fun cursorToRoot(c: Cursor): RootEntity = RootEntity(
        rootId = c.getLong(0),
        letters = c.getString(1),
        lettersSpaced = c.getString(2),
        occurrences = c.getInt(3),
        formsCount = c.getInt(4),
        meaningAr = if (c.isNull(5)) null else c.getString(5)
    )

    private fun cursorToAyah(c: Cursor): AyahEntity = AyahEntity(
        surahNo = c.getInt(0),
        ayahNo = c.getInt(1),
        textUthmani = c.getString(2),
        textSimple = c.getString(3)
    )

    companion object {
        private const val DB_NAME = "quran-roots.db"
        /** Bump when the asset DB schema changes so clients re-copy from assets. */
        private const val DB_VERSION = 16
    }
}
