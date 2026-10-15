package com.quranroots.app.ui.screens.letters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.viewmodel.LetterSort
import com.quranroots.app.viewmodel.LettersViewModel
import androidx.compose.ui.platform.LocalContext

@Composable
fun LettersScreen(
    onLetterClick: (String) -> Unit,
    onMuqattaatClick: () -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val vm: LettersViewModel = viewModel(factory = LettersViewModel.Factory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "الحروف",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
        )

        // Sort chips.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(LetterSort.entries) { sort ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { vm.setSort(sort) },
                    label = { Text(sort.labelAr) }
                )
            }
        }

        if (state.loading) {
            Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                MuqattaatBanner(onClick = onMuqattaatClick)
            }
            items(state.letters, key = { it.letter }) { letter ->
                LetterCell(letter, state.sort, onClick = { onLetterClick(letter.letter) })
            }
        }
    }
}

@Composable
private fun MuqattaatBanner(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "الحروف المقطَّعة (فواتح السور)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "١٤ حرفًا نورانيًّا افتُتحت بها ٢٩ سورة — اضغط للتفصيل",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** The secondary stat shown under the glyph depends on the active sort. */
@Composable
private fun LetterCell(letter: LetterEntity, sort: LetterSort, onClick: () -> Unit) {
    val (statLabel, statValue) = when (sort) {
        LetterSort.ABJAD, LetterSort.ABJAD_VALUE -> "جُمّل" to letter.abjadValue.toString()
        LetterSort.FREQ_TEXT -> "تكرار" to formatK(letter.freqUthmani)
        LetterSort.ROOT_COUNT -> "جذور" to letter.rootCount.toString()
        LetterSort.WEIGHTED -> "مواضع" to formatK(letter.weightedOcc)
        else -> "جذور" to letter.rootCount.toString()
    }
    Card(
        onClick = onClick,
        colors = if (letter.isMuqattaa)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                letter.letter,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "$statLabel $statValue",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatK(n: Int): String =
    if (n >= 1000) "%.1f ألف".format(n / 1000.0) else n.toString()
