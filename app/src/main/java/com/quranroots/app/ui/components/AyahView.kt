package com.quranroots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.ui.theme.Highlight
import com.quranroots.app.ui.theme.QuranFont

/**
 * Render an ayah word-by-word. Words whose `wordNo` is in
 * `highlightedWordNos` get a yellow highlight; tapping a highlighted word
 * invokes `onWordClick` (used to hide all ayahs of that lemma).
 * Long-pressing ANY word opens «تصحيح الجذر» (خ-65).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahView(
    words: List<WordEntity>,
    highlightedWordNos: Set<Int>,
    relatedHighlightedWordNos: Set<Int> = emptySet(),
    onWordClick: ((WordEntity) -> Unit)? = null,
    /** Per-word background (study view: one color per root). Takes precedence. */
    wordColors: Map<Int, Color> = emptyMap(),
    /** Smaller type for long prose (hadith matn). */
    compact: Boolean = false
) {
    val dark = isSystemInDarkTheme()
    val highlightBg: Color = if (dark) Highlight.WordBgDark else Highlight.WordBg
    val relatedBg: Color = if (dark) Highlight.RelatedBgDark else Highlight.RelatedBg
    val report = LocalWordReport.current
    val reportHid = LocalReportHid.current
    val contextRootId = LocalContextRootId.current

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (w in words) {
            val isHi = w.wordNo in highlightedWordNos
            val isRel = w.wordNo in relatedHighlightedWordNos
            val bg = when {
                w.wordNo in wordColors -> wordColors.getValue(w.wordNo)
                isHi -> highlightBg
                isRel -> relatedBg
                else -> null
            }
            val mod = Modifier
                .let { m -> if (bg != null) m.clip(RoundedCornerShape(6.dp)).background(bg) else m }
                .let { m ->
                    val click = if (isHi && onWordClick != null) ({ onWordClick(w) }) else null
                    if (click == null && report == null) m
                    // Tap gestures (no ripple) so plain words stay visually inert;
                    // only highlighted words react to a tap.
                    else m.pointerInput(w, isHi, report, onWordClick, reportHid, contextRootId) {
                        detectTapGestures(
                            onTap = { click?.invoke() },
                            onLongPress = { report?.request(w, reportHid, if (isHi) contextRootId else null) }
                        )
                    }
                }
            Text(
                text = w.text,
                modifier = mod,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = QuranFont,
                // Amiri Quran has no bold cut; synthesized bold smears the marks,
                // so emphasis comes from the highlight background only.
                fontWeight = FontWeight.Normal,
                fontSize = if (compact) 19.sp else 24.sp,
                lineHeight = if (compact) 34.sp else 44.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
