package com.quranroots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.quranroots.app.data.db.entities.Hadith
import com.quranroots.app.data.db.entities.WordEntity

/**
 * A Sahihayn hadith: source line (book · number · باب), collapsible isnad,
 * and the matn with root words highlighted — same word renderer as ayahs.
 */
@Composable
fun HadithCard(
    h: Hadith,
    highlighted: Set<Int> = emptySet(),
    relatedHighlighted: Set<Int> = emptySet(),
    wordColors: Map<Int, Color> = emptyMap(),
    divider: Boolean = true
) {
    var showIsnad by remember(h.hid) { mutableStateOf(false) }
    val words = remember(h.hid) {
        h.words.mapIndexed { i, t -> WordEntity(0, 0, i + 1, t, null, null) }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (h.isOutside) {
                        Text(
                            "خارج الصحيحين",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        "${h.book} · ${h.hadithNo}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                h.section?.takeIf { it.isNotBlank() }?.let {
                    Text(if (h.isOutside) "الحكم: $it" else "باب: $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            if (!h.isnad.isNullOrBlank()) {
                TextButton(onClick = { showIsnad = !showIsnad }) {
                    Text(if (h.isOutside) "الراوي" else if (showIsnad) "إخفاء الإسناد" else "الإسناد")
                }
            }
        }
        if (showIsnad) {
            Text(
                h.isnad.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        h.url?.let { url ->
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(
                onClick = { uri.openUri(url) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("المصدر: الدرر السنية", style = MaterialTheme.typography.labelMedium) }
        }
        AyahView(
            words = words,
            highlightedWordNos = highlighted,
            relatedHighlightedWordNos = relatedHighlighted,
            wordColors = wordColors,
            compact = true
        )
        if (divider) HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * خ-26: one hadith with its narrations. The primary narration is shown in
 * full; the other Sahihayn narrations open under «روايات أخرى», and
 * narrations in other books are counted only (their texts are not shipped).
 */
@Composable
fun HadithUnitCard(
    primary: Hadith,
    others: List<Hadith>,
    highlighted: (Long) -> Set<Int> = { emptySet() },
    relatedHighlighted: (Long) -> Set<Int> = { emptySet() },
    wordColors: (Long) -> Map<Int, Color> = { emptyMap() }
) {
    var expanded by remember(primary.unitId) { mutableStateOf(false) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        HadithCard(
            h = primary,
            highlighted = highlighted(primary.hid),
            relatedHighlighted = relatedHighlighted(primary.hid),
            wordColors = wordColors(primary.hid),
            divider = false
        )
        if (others.isNotEmpty() || primary.otherNarrations > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (others.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            (if (expanded) "إخفاء الروايات" else "روايات أخرى في الصحيحين") +
                                " (${others.size})"
                        )
                    }
                }
                if (primary.otherNarrations > 0) {
                    Text(
                        (if (primary.isOutside) "روايات أخرى خارج الصحيحين: " else "وفي كتب أخرى: ") +
                            primary.otherNarrations,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .drawBehind {
                        drawRect(
                            color = lineColor,
                            size = Size(2.dp.toPx(), size.height)
                        )
                    }
                    .padding(start = 10.dp)
            ) {
                others.forEach { o ->
                    HadithCard(
                        h = o,
                        highlighted = highlighted(o.hid),
                        relatedHighlighted = relatedHighlighted(o.hid),
                        wordColors = wordColors(o.hid),
                        divider = false
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}
