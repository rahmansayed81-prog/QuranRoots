package com.quranroots.app.ui.screens.wazn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.WaznEntry
import com.quranroots.app.ui.components.RootChip
import com.quranroots.app.ui.theme.QuranFont
import com.quranroots.app.viewmodel.WaznViewModel

/** «وزن الأصل» for a lemma's ميزان (فَاعِل), «وزن الكلمة» for a word's (فَاعِلُونَ). */
fun WaznEntry.kindAr(): String = if (isLemma) "وزن الأصل" else "وزن الكلمة"

/**
 * خ-40 / خ-42: every Quran word on one ميزان, grouped by root. The التصريف
 * chips narrow the list; «بنفس الحروف» switches to the same letters with
 * other vowels (فَعَلَ / فَعْل / فِعْل).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaznScreen(
    waznId: Long,
    onBack: () -> Unit,
    onRootClick: (Long) -> Unit,
    onWaznClick: (Long) -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val vm: WaznViewModel = viewModel(key = "wazn$waznId", factory = WaznViewModel.Factory(app))
    LaunchedEffect(waznId) { vm.load(waznId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text(
                state.wazn?.text ?: "…",
                fontFamily = QuranFont,
                fontSize = 30.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
            state.wazn?.let { w ->
                Text(
                    "  ${w.kindAr()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.loading) {
            Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
            return@Column
        }
        val w = state.wazn ?: return@Column

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // خ-40 م2: one ميزان, two sources — the Quran or the hadith.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.showHadith,
                        onClick = { vm.setShowHadith(false) },
                        enabled = w.words > 0,
                        label = { Text("القرآن (${w.words})") }
                    )
                    FilterChip(
                        selected = state.showHadith,
                        onClick = { vm.setShowHadith(true) },
                        enabled = state.hadithGroups.isNotEmpty(),
                        label = { Text("الأحاديث (${w.hadith})") }
                    )
                }
            }
            if (state.showHadith) {
                item {
                    Text(
                        "${state.hadithGroups.size} جذرًا · الميزان آلي: من نفس الكلمة في القرآن، وإلا بتحليل CAMeL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(state.hadithGroups, key = { "h${it.root.rootId}" }) { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RootChip(root = g.root, label = g.root.lettersSpaced, onClick = { onRootClick(g.root.rootId) })
                                Text(
                                    "  ${g.count} موضعًا",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                g.forms.joinToString("  ·  ") { (t, n) -> if (n > 1) "$t ($n)" else t },
                                fontSize = 18.sp,
                                lineHeight = 32.sp
                            )
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Text(
                    "${w.words} موضعًا · ${w.roots} جذرًا · الميزان مستخرج آليًا من تحليل QAC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.labels.size > 1) {
                item { Text("التصريف", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.labels.forEach { (label, n) ->
                            FilterChip(
                                selected = state.selectedLabel == label,
                                onClick = { vm.selectLabel(label) },
                                label = { Text("$label  ($n)", style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            } else state.labels.firstOrNull()?.let { (label, _) ->
                item { Text("التصريف: $label", style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.siblings.isNotEmpty()) {
                item { Text("بنفس الحروف", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.siblings.forEach { s ->
                            SuggestionChip(
                                onClick = { onWaznClick(s.id) },
                                label = {
                                    Text("${s.text}  ·  ${s.words}${if (s.isLemma != w.isLemma) "  (${s.kindAr()})" else ""}",
                                        fontFamily = QuranFont)
                                }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "الجذور (${state.groups.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(state.groups, key = { it.root.rootId }) { g ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RootChip(root = g.root, label = g.root.lettersSpaced, onClick = { onRootClick(g.root.rootId) })
                            Text(
                                "  ${g.count} موضعًا",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            g.forms.joinToString("  ·  ") { (t, n) -> if (n > 1) "$t ($n)" else t },
                            fontFamily = QuranFont,
                            fontSize = 20.sp,
                            lineHeight = 36.sp
                        )
                    }
                }
            }
        }
    }
}
