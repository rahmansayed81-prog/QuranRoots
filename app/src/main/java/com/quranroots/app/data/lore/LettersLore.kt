package com.quranroots.app.data.lore

import android.content.Context
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Data models for the curated letters lore (assets/letters_lore.json).
// ---------------------------------------------------------------------------

data class MakhrajZone(
    val key: String,
    val name: String,
    val point: String,
    val explain: String,
    val letters: List<String>
)

data class SifaExplain(val name: String, val explain: String)

data class MuqattaaSurah(val surah: Int, val opening: String)

data class MuqattaatInfo(
    val title: String,
    val intro: String,
    val letters: List<String>,
    val surahs: List<MuqattaaSurah>
)

data class LetterLore(
    val letter: String,
    val makhrajKey: String,
    val sifat: List<String>,
    val ibnJinni: String,
    val extended: String
)

/**
 * Parsed, in-memory view of `assets/letters_lore.json`. Loaded once and cached
 * on the [App]. All content is curated (no runtime network).
 */
class LettersLore(
    val makharijIntro: String,
    val zones: List<MakhrajZone>,
    val sifatIntro: String,
    val sifat: Map<String, String>,
    val muqattaat: MuqattaatInfo,
    val letters: Map<String, LetterLore>
) {
    private val zonesByKey = zones.associateBy { it.key }
    fun zone(key: String): MakhrajZone? = zonesByKey[key]

    companion object {
        private const val ASSET = "letters_lore.json"

        fun load(context: Context): LettersLore {
            val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(raw)

            val mg = root.getJSONObject("makharij_glossary")
            val zonesArr = mg.getJSONArray("zones")
            val zones = ArrayList<MakhrajZone>(zonesArr.length())
            for (i in 0 until zonesArr.length()) {
                val z = zonesArr.getJSONObject(i)
                zones += MakhrajZone(
                    key = z.getString("key"),
                    name = z.getString("name"),
                    point = z.getString("point"),
                    explain = z.getString("explain"),
                    letters = z.getJSONArray("letters").toStringList()
                )
            }

            val sg = root.getJSONObject("sifat_glossary")
            val sifatItems = sg.getJSONObject("items")
            val sifat = LinkedHashMap<String, String>()
            sifatItems.keys().forEach { k -> sifat[k] = sifatItems.getString(k) }

            val mq = root.getJSONObject("muqattaat")
            val surahArr = mq.getJSONArray("surahs")
            val surahs = ArrayList<MuqattaaSurah>(surahArr.length())
            for (i in 0 until surahArr.length()) {
                val s = surahArr.getJSONObject(i)
                surahs += MuqattaaSurah(s.getInt("surah"), s.getString("opening"))
            }
            val muqattaat = MuqattaatInfo(
                title = mq.getString("title"),
                intro = mq.getString("intro"),
                letters = mq.getJSONArray("letters").toStringList(),
                surahs = surahs
            )

            val lettersObj = root.getJSONObject("letters")
            val letters = LinkedHashMap<String, LetterLore>()
            lettersObj.keys().forEach { k ->
                val l = lettersObj.getJSONObject(k)
                letters[k] = LetterLore(
                    letter = k,
                    makhrajKey = l.getString("makhraj"),
                    sifat = l.getJSONArray("sifat").toStringList(),
                    ibnJinni = l.getString("ibn_jinni"),
                    extended = l.getString("extended")
                )
            }

            return LettersLore(
                makharijIntro = mg.getString("intro"),
                zones = zones,
                sifatIntro = sg.getString("intro"),
                sifat = sifat,
                muqattaat = muqattaat,
                letters = letters
            )
        }

        private fun org.json.JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }
    }
}
