package com.quranroots.app.sync

import com.quranroots.app.data.db.QuranDataSource
import com.quranroots.app.data.db.entities.AyahNoteEntity
import com.quranroots.app.data.db.entities.AyahTagEntity
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.RootNoteEntity
import com.quranroots.app.data.db.entities.StudyEntity
import com.quranroots.app.data.db.entities.StudyRootEntity
import com.quranroots.app.data.db.entities.TagEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenAyahEntity
import com.quranroots.app.data.db.entities.WorkspaceItemEntity
import com.quranroots.app.data.repository.WorkspaceSnapshot
import com.quranroots.app.data.repository.groupHadithByUnit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One file to put on Drive. `key` is stable across syncs (used to find it). */
data class ExportFile(
    val key: String,
    val kind: String,          // "study" | "index" | "backup"
    val name: String,
    val mime: String,
    val content: String
)

/**
 * Turns the workspace into Drive files:
 *  - one Markdown file per study (readable by the user, by Claude, by QuranVault),
 *  - an index Markdown (roots outside studies, tags),
 *  - one JSON backup that restores everything (roots referenced by letters,
 *    so a rebuilt Quran DB with different ids still restores correctly).
 */
class StudyExporter(private val quran: QuranDataSource) {

    fun export(snap: WorkspaceSnapshot): List<ExportFile> {
        val rootIds = (snap.items.map { it.rootId } + snap.studyRoots.map { it.rootId } +
            snap.rootNotes.map { it.rootId }).distinct()
        val roots = quran.rootsByIds(rootIds).associateBy { it.rootId }
        val surahNames = HashMap<Int, String>()
        fun surahName(n: Int) = surahNames.getOrPut(n) { quran.surah(n)?.nameAr ?: n.toString() }

        val ayahNotes = snap.ayahNotes.associate { (it.surahNo to it.ayahNo) to it.text }
        val tagsById = snap.tags.associateBy { it.id }
        val ayahTags = snap.ayahTags.groupBy({ it.surahNo to it.ayahNo }, { it.tagId })
        val rootNotes = snap.rootNotes.associate { it.rootId to it.text }
        val itemByRoot = snap.items.associateBy { it.rootId }
        val hiddenByItem = snap.hiddenAyahs.groupBy({ it.itemId }, { it.surahNo to it.ayahNo })
            .mapValues { it.value.toSet() }
        fun hiddenFor(rootId: Long): Set<Pair<Int, Int>> =
            itemByRoot[rootId]?.let { hiddenByItem[it.id] }.orEmpty()

        val files = mutableListOf<ExportFile>()
        val linksByStudy = snap.studyRoots.groupBy { it.studyId }

        for (study in snap.studies) {
            val studyRoots = linksByStudy[study.id].orEmpty()
                .sortedBy { it.addedAt }.mapNotNull { roots[it.rootId] }
            files += ExportFile(
                key = "study-${study.id}",
                kind = "study",
                name = safeFileName(study.name) + ".md",
                mime = "text/markdown",
                content = studyMarkdown(study, studyRoots, ::surahName, ayahNotes, ayahTags,
                    tagsById, rootNotes, ::hiddenFor)
            )
        }

        val inStudy = snap.studyRoots.map { it.rootId }.toSet()
        files += ExportFile(
            key = "index", kind = "index", name = "_فهرس.md", mime = "text/markdown",
            content = indexMarkdown(snap, roots, inStudy, rootNotes)
        )
        files += ExportFile(
            key = "backup", kind = "backup", name = "quranroots-backup.json",
            mime = "application/json", content = backupJson(snap, roots).toString(1)
        )
        return files
    }

    // --- Markdown ------------------------------------------------------------

    private fun studyMarkdown(
        study: StudyEntity,
        roots: List<RootEntity>,
        surahName: (Int) -> String,
        ayahNotes: Map<Pair<Int, Int>, String>,
        ayahTags: Map<Pair<Int, Int>, List<Long>>,
        tags: Map<Long, TagEntity>,
        rootNotes: Map<Long, String>,
        hiddenFor: (Long) -> Set<Pair<Int, Int>>
    ): String {
        // ayah → indexes of the study roots it contains
        val byAyah = LinkedHashMap<Pair<Int, Int>, MutableSet<Int>>()
        roots.forEachIndexed { i, r ->
            for (w in quran.wordsForRoot(r.rootId)) byAyah.getOrPut(w.surahNo to w.ayahNo) { sortedSetOf() } += i
        }
        // Hidden in the study = hidden for every study root present (same rule as the app).
        val visible = byAyah.filter { (key, idx) -> !idx.all { key in hiddenFor(roots[it].rootId) } }
        val ordered = visible.entries.sortedWith(
            compareBy({ -it.value.size }, { it.key.first }, { it.key.second })
        )
        val shared = ordered.filter { it.value.size >= 2 }
        val single = ordered.filter { it.value.size < 2 }

        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("type: quranroots-study\n")
        sb.append("study_id: ${study.id}\n")
        sb.append("name: ${yaml(study.name)}\n")
        sb.append("roots: [${roots.joinToString(", ") { yaml(it.lettersSpaced) }}]\n")
        sb.append("ayahs: ${visible.size}\n")
        sb.append("shared_ayahs: ${shared.size}\n")
        sb.append("exported: ${isoNow()}\n")
        sb.append("---\n\n")
        sb.append("# ${study.name}\n\n")

        sb.append("## الرابط الجامع\n\n")
        sb.append(study.linkNote?.takeIf { it.isNotBlank() } ?: "_(لم يُكتب بعد)_").append("\n\n")

        sb.append("## الجذور\n\n")
        roots.forEach { r ->
            sb.append("### ${r.lettersSpaced}\n")
            sb.append("- المواضع: ${r.occurrences} · التصريفات: ${r.formsCount}\n")
            rootNotes[r.rootId]?.let { sb.append("- ملاحظة: ").append(oneBlock(it)).append("\n") }
            sb.append("\n")
        }

        fun appendAyahs(title: String, list: List<Map.Entry<Pair<Int, Int>, Set<Int>>>) {
            if (list.isEmpty()) return
            sb.append("## $title (${list.size})\n\n")
            for ((key, idx) in list) {
                val (s, a) = key
                val text = quran.ayah(s, a)?.textUthmani ?: continue
                val rootLabel = idx.joinToString("، ") { roots[it].lettersSpaced }
                sb.append("- **${surahName(s)} $s:$a** — [$rootLabel]\n")
                sb.append("  > $text\n")
                ayahTags[key]?.mapNotNull { tags[it]?.name }?.takeIf { it.isNotEmpty() }?.let {
                    sb.append("  - وسوم: ${it.joinToString("، ")}\n")
                }
                ayahNotes[key]?.let { sb.append("  - ملاحظة: ").append(oneBlock(it)).append("\n") }
            }
            sb.append("\n")
        }
        appendAyahs("آيات اجتمع فيها أكثر من جذر", shared)
        appendAyahs(if (shared.isEmpty()) "الآيات" else "بقية الآيات", single)

        // خ-23: Sahihayn hadith joining ≥2 study roots (capped; automatic root analysis).
        // خ-26: narrations of one hadith count as one; roots are the union over them.
        if (roots.size >= 2) {
            val rootsByHid = HashMap<Long, MutableSet<Int>>()
            val unitByHid = HashMap<Long, Long>()
            val reps = HashSet<Long>()
            roots.forEachIndexed { i, r ->
                val hits = quran.hadithHitsForRoot(r.rootId)
                hits.words.keys.forEach { rootsByHid.getOrPut(it) { sortedSetOf() } += i }
                unitByHid += hits.unitByHid
                reps += hits.repHids
            }
            val units = groupHadithByUnit(rootsByHid.keys, unitByHid, reps) { rootsByHid.getValue(it).size }
                .map { g -> g to g.hids.flatMap { rootsByHid.getValue(it) }.toSortedSet() }
                .filter { it.second.size >= 2 }
                .sortedWith(compareBy({ -it.second.size }, { it.first.primary }))
            if (units.isNotEmpty()) {
                val shown = units.take(HADITH_CAP)
                sb.append("## أحاديث اجتمع فيها أكثر من جذر (${units.size})\n\n")
                sb.append("_من صحيحي البخاري ومسلم — جذور الحديث بتحليل آلي؛ روايات الحديث الواحد معدودة مرة")
                if (units.size > HADITH_CAP) sb.append("؛ أول $HADITH_CAP فقط")
                sb.append("._\n\n")
                val texts = quran.hadiths(shown.flatMap { it.first.hids }).associateBy { it.hid }
                for ((g, idx) in shown) {
                    val h = texts[g.primary] ?: continue
                    sb.append("- **${h.book} ${h.hadithNo}**")
                    if (h.isOutside) {
                        sb.append(" (خارج الصحيحين؛ ${h.section ?: "صحيح"}")
                        h.url?.let { sb.append(" — [الدرر السنية]($it)") }
                        sb.append(")")
                    }
                    sb.append(" — [${idx.joinToString("، ") { roots[it].lettersSpaced }}]")
                    val others = g.others.mapNotNull { texts[it] }
                    if (others.isNotEmpty()) {
                        sb.append(" — روايات أخرى: ").append(others.joinToString("، ") { "${it.book} ${it.hadithNo}" })
                    }
                    if (h.otherNarrations > 0) sb.append(" — وفي كتب أخرى: ${h.otherNarrations}")
                    sb.append("\n")
                    sb.append("  > ${h.words.joinToString(" ")}\n")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun indexMarkdown(
        snap: WorkspaceSnapshot,
        roots: Map<Long, RootEntity>,
        inStudy: Set<Long>,
        rootNotes: Map<Long, String>
    ): String {
        val sb = StringBuilder()
        sb.append("---\ntype: quranroots-index\nexported: ${isoNow()}\n---\n\n")
        sb.append("# ورشة جذور القرآن\n\n## الدراسات\n\n")
        val linksByStudy = snap.studyRoots.groupBy { it.studyId }
        if (snap.studies.isEmpty()) sb.append("_(لا دراسات)_\n")
        snap.studies.sortedBy { it.createdAt }.forEach { s ->
            val rs = linksByStudy[s.id].orEmpty().mapNotNull { roots[it.rootId]?.lettersSpaced }
            sb.append("- **${s.name}** — ${rs.joinToString("، ")}\n")
        }
        sb.append("\n## جذور خارج الدراسات\n\n")
        val loose = snap.items.map { it.rootId }.filter { it !in inStudy }.mapNotNull { roots[it] }
        if (loose.isEmpty()) sb.append("_(لا شيء)_\n")
        loose.forEach { r ->
            sb.append("- ${r.lettersSpaced} (${r.occurrences} موضعًا)")
            rootNotes[r.rootId]?.let { sb.append(" — ").append(oneBlock(it)) }
            sb.append("\n")
        }
        sb.append("\n## الوسوم\n\n")
        val counts = snap.ayahTags.groupingBy { it.tagId }.eachCount()
        if (snap.tags.isEmpty()) sb.append("_(لا وسوم)_\n")
        snap.tags.forEach { t -> sb.append("- ${t.name}: ${counts[t.id] ?: 0} آية\n") }
        return sb.toString()
    }

    // --- JSON backup -----------------------------------------------------------

    private fun backupJson(snap: WorkspaceSnapshot, roots: Map<Long, RootEntity>): JSONObject {
        fun rootRef(id: Long) = JSONObject().put("id", id).put("letters", roots[id]?.letters ?: "")
        return JSONObject()
            .put("format", "quranroots-backup").put("version", 1).put("exported", isoNow())
            .put("items", JSONArray(snap.items.map {
                JSONObject().put("item_id", it.id).put("root", rootRef(it.rootId)).put("created_at", it.createdAt)
            }))
            .put("hidden_ayahs", JSONArray(snap.hiddenAyahs.map {
                JSONObject().put("item_id", it.itemId).put("s", it.surahNo).put("a", it.ayahNo)
            }))
            .put("studies", JSONArray(snap.studies.map {
                JSONObject().put("id", it.id).put("name", it.name).put("created_at", it.createdAt)
                    .put("link_note", it.linkNote ?: JSONObject.NULL)
            }))
            .put("study_roots", JSONArray(snap.studyRoots.map {
                JSONObject().put("study_id", it.studyId).put("root", rootRef(it.rootId)).put("added_at", it.addedAt)
            }))
            .put("ayah_notes", JSONArray(snap.ayahNotes.map {
                JSONObject().put("s", it.surahNo).put("a", it.ayahNo).put("text", it.text).put("updated_at", it.updatedAt)
            }))
            .put("root_notes", JSONArray(snap.rootNotes.map {
                JSONObject().put("root", rootRef(it.rootId)).put("text", it.text).put("updated_at", it.updatedAt)
            }))
            .put("tags", JSONArray(snap.tags.map {
                JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
            }))
            .put("ayah_tags", JSONArray(snap.ayahTags.map {
                JSONObject().put("tag_id", it.tagId).put("s", it.surahNo).put("a", it.ayahNo)
            }))
    }

    /** Parse a backup. Roots resolve by letters first (stable), id second. */
    fun parseBackup(json: String): WorkspaceSnapshot {
        val o = JSONObject(json)
        require(o.optString("format") == "quranroots-backup") { "ليس ملف نسخة احتياطية لجذور القرآن" }
        fun resolve(ref: JSONObject): Long? {
            val letters = ref.optString("letters")
            if (letters.isNotEmpty()) quran.rootByLetters(letters)?.let { return it.rootId }
            val id = ref.optLong("id", -1)
            return if (id > 0 && quran.rootById(id) != null) id else null
        }
        fun arr(name: String) = o.optJSONArray(name) ?: JSONArray()
        fun <T> JSONArray.mapObj(f: (JSONObject) -> T?): List<T> =
            (0 until length()).mapNotNull { f(getJSONObject(it)) }

        val items = arr("items").mapObj { j ->
            resolve(j.getJSONObject("root"))?.let {
                WorkspaceItemEntity(j.getLong("item_id"), it, j.getLong("created_at"))
            }
        }
        val itemIds = items.map { it.id }.toSet()
        return WorkspaceSnapshot(
            items = items,
            hiddenAyahs = arr("hidden_ayahs").mapObj { j ->
                j.getLong("item_id").takeIf { it in itemIds }?.let {
                    WorkspaceHiddenAyahEntity(it, j.getInt("s"), j.getInt("a"))
                }
            },
            studies = arr("studies").mapObj { j ->
                StudyEntity(j.getLong("id"), j.getString("name"), j.getLong("created_at"),
                    if (j.isNull("link_note")) null else j.getString("link_note"))
            },
            studyRoots = arr("study_roots").mapObj { j ->
                resolve(j.getJSONObject("root"))?.let {
                    StudyRootEntity(j.getLong("study_id"), it, j.getLong("added_at"))
                }
            }.distinctBy { it.studyId to it.rootId },
            ayahNotes = arr("ayah_notes").mapObj { j ->
                AyahNoteEntity(j.getInt("s"), j.getInt("a"), j.getString("text"), j.getLong("updated_at"))
            },
            rootNotes = arr("root_notes").mapObj { j ->
                resolve(j.getJSONObject("root"))?.let {
                    RootNoteEntity(it, j.getString("text"), j.getLong("updated_at"))
                }
            }.distinctBy { it.rootId },
            tags = arr("tags").mapObj { j -> TagEntity(j.getLong("id"), j.getString("name"), j.getInt("color")) },
            ayahTags = arr("ayah_tags").mapObj { j ->
                AyahTagEntity(j.getLong("tag_id"), j.getInt("s"), j.getInt("a"))
            }
        )
    }

    private companion object { const val HADITH_CAP = 60 }

    private fun yaml(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun oneBlock(s: String) = s.trim().replace("\n", " ⏎ ")
    private fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "دراسة" }
    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }.format(Date())
}
