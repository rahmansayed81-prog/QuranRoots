package com.quranroots.app.data.repository

/**
 * خ-26: one hadith, many narrations. Narrations (hids) that Dorar put in the
 * same unit are shown as one entry: a primary narration plus the others
 * behind «N روايات». The primary is the narration that best serves the
 * current view (most matching words / most study roots), then the unit's
 * representative, then the lowest hid (Bukhari precedes Muslim).
 */
data class HadithGroup(val unitId: Long, val primary: Long, val others: List<Long>) {
    val size: Int get() = others.size + 1
    val hids: List<Long> get() = listOf(primary) + others
}

fun groupHadithByUnit(
    hids: Collection<Long>,
    unitByHid: Map<Long, Long>,
    repHids: Set<Long>,
    score: (Long) -> Int
): List<HadithGroup> =
    hids.groupBy { unitByHid[it] ?: it }
        .map { (unit, members) ->
            val ordered = members.sortedWith(
                compareBy({ -score(it) }, { if (it in repHids) 0 else 1 }, { it })
            )
            HadithGroup(unit, ordered.first(), ordered.drop(1))
        }
