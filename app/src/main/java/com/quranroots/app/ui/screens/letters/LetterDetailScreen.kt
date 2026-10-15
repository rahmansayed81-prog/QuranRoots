package com.quranroots.app.ui.screens.letters

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.data.db.entities.LetterRoot
import com.quranroots.app.ui.components.MakhrajDiagram
import com.quranroots.app.viewmodel.LetterDetailState
import com.quranroots.app.viewmodel.LetterDetailViewModel
import com.quranroots.app.viewmodel.LetterRootSort

@Composable
fun LetterDetailScreen(
    letter: String,
    onBack: () -> Unit,
    onRootClick: (Long) -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val vm: LetterDetailViewModel = viewModel(factory = LetterDetailViewModel.Factory(app))
    LaunchedEffect(letter) { vm.load(letter) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text(
                state.letter?.letter ?: letter,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            if (state.letter?.isMuqattaa == true) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("نوراني") },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        if (state.loading) {
            Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StatsCard(state) }
            item { MakhrajCard(state) }
            if (state.sifatExplained.isNotEmpty()) item { SifatCard(state) }
            item { LoreCard(state) }
            if (state.nonCombining.isNotEmpty()) item { NonCombiningCard(state) }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        "جذور الحرف (${state.roots.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    LetterRootSort.entries.forEach { s ->
                        FilterChip(
                            selected = state.rootSort == s,
                            onClick = { vm.setRootSort(s) },
                            label = { Text(s.labelAr) },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            items(state.roots, key = { it.root.rootId }) { lr ->
                RootRow(lr, letter, onClick = { onRootClick(lr.root.rootId) })
            }
        }
    }
}

@Composable
private fun StatsCard(state: LetterDetailState) {
    val l = state.letter ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            FlowRowStats(l)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowStats(l: LetterEntity) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("القيمة (جُمّل)", l.abjadValue.toString())
        Stat("ترتيب أبجدي", l.abjadOrder.toString())
        Stat("ترتيب هجائي", l.hijaOrder.toString())
        Stat("عدد الجذور", l.rootCount.toString())
        Stat("تكرار (عثماني)", l.freqUthmani.toString())
        Stat("تكرار (إملائي)", l.freqSimple.toString())
        Stat("مجموع المواضع", l.weightedOcc.toString())
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MakhrajCard(state: LetterDetailState) {
    val zone = state.zone ?: return
    var showGlossary by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("المخرج", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                zone.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(zone.point, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            MakhrajDiagram(makhrajKey = zone.key, modifier = Modifier.padding(vertical = 8.dp))

            Text(zone.explain, style = MaterialTheme.typography.bodyMedium)

            ExpandHeader("ما معنى تصنيفات المخارج؟", showGlossary) { showGlossary = !showGlossary }
            AnimatedVisibility(visible = showGlossary) {
                Text(
                    state.makharijIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SifatCard(state: LetterDetailState) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("الصفات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.sifatExplained.forEach { (name, _) ->
                    AssistChip(onClick = { expanded = true }, label = { Text(name) })
                }
            }
            ExpandHeader("شرح الصفات", expanded) { expanded = !expanded }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 4.dp)) {
                    state.sifatExplained.forEach { (name, explain) ->
                        if (explain.isNotBlank()) {
                            Text(
                                "• $name: $explain",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoreCard(state: LetterDetailState) {
    val lore = state.lore ?: return
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("عند علماء الحروف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                lore.ibnJinni,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp)
            )
            ExpandHeader("للاستزادة", expanded) { expanded = !expanded }
            AnimatedVisibility(visible = expanded) {
                Text(
                    lore.extended,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NonCombiningCard(state: LetterDetailState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("تنافر الحروف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "حروفٌ لا تجتمع مع «${state.letter?.letter}» في جذرٍ واحد في القرآن — وهي ظاهرة رصدها الخليل وابن جني لتقارب المخارج:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.nonCombining.forEach { ch ->
                    SuggestionChip(onClick = {}, label = { Text(ch, style = MaterialTheme.typography.titleMedium) })
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RootRow(lr: LetterRoot, letter: String, onClick: () -> Unit) {
    val onLongClick = com.quranroots.app.ui.components.rememberAddToStudy(lr.root)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Text(
            positionLabel(lr.position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            lr.root.lettersSpaced,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        Text(
            "${lr.root.occurrences} موضعًا",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExpandHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp)
    ) {
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private fun positionLabel(pos: Int): String = when (pos) {
    0 -> "فاء الكلمة"
    1 -> "عين الكلمة"
    2 -> "لام الكلمة"
    else -> "الموضع ${pos + 1}"
}
