package com.quranroots.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.RootNotFound
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.R
import com.quranroots.app.data.db.entities.WordMatch
import com.quranroots.app.ui.screens.home.RootListRow
import com.quranroots.app.viewmodel.SearchMode
import com.quranroots.app.viewmodel.SearchViewModel

@Composable
fun SearchScreen(onRootClick: (Long) -> Unit, onWaznClick: (Long) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as App
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(app))

    val query by vm.query.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val wordResults by vm.wordResults.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val waznResults by vm.waznResults.collectAsStateWithLifecycle()
    val catalogLemma by vm.catalogLemma.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { vm.setMode(m) },
                    label = { Text(m.labelAr) }
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = {
                Text(
                    when (mode) {
                        SearchMode.WORD -> "اكتب كلمة (مثال: يعلمون، الصلاة)…"
                        SearchMode.WAZN -> "اكتب وزنًا (مثال: فاعلون، مفعول، استفعل)…"
                        else -> stringResource(R.string.search_hint)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (mode == SearchMode.WORD) {
            Text(
                "اكتب أى كلمة من القرآن وسيظهر جذرها.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (mode == SearchMode.WAZN) {
            if (query.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("تصفّح:", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = catalogLemma, onClick = { vm.setCatalogLemma(true) },
                        label = { Text("وزن الأصل") })
                    FilterChip(selected = !catalogLemma, onClick = { vm.setCatalogLemma(false) },
                        label = { Text("وزن الكلمة") })
                }
            } else {
                Text(
                    "يُبحث بالحروف دون التشكيل؛ ستظهر كل الأوزان المكتوبة بها (فعل ← فَعَلَ، فَعْل، فِعْل …).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (loading) Text(stringResource(R.string.loading))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (mode) {
                SearchMode.ROOT -> {
                    notFound?.let { nf ->
                        item { NotFoundCard(nf, onRootClick) }
                        if (results.isNotEmpty()) item {
                            Text(
                                "جذور تبدأ بـ «${nf.query.take(2)}» (للاستئناس فقط)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(results) { root -> RootListRow(root, onClick = { onRootClick(root.rootId) }) }
                }
                SearchMode.WORD ->
                    items(wordResults) { m -> WordMatchRow(m, onClick = { onRootClick(m.root.rootId) }) }
                SearchMode.WAZN -> {
                    if (query.isNotBlank() && waznResults.isEmpty() && !loading) item {
                        Text("لا يوجد في القرآن وزن بهذه الحروف.", style = MaterialTheme.typography.bodyMedium)
                    }
                    items(waznResults, key = { it.id }) { w -> WaznRow(w, onClick = { onWaznClick(w.id) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotFoundCard(nf: RootNotFound, onRootClick: (Long) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "الجذر «${nf.query.toList().joinToString(" ")}» غير وارد في القرآن",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            nf.external?.let { ext ->
                if (ext.gloss.isNotBlank()) {
                    Text(
                        "معناه (Wiktionary): ${ext.gloss}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (nf.variants.isNotEmpty()) {
                Text("لعلك تقصد (باختلاف حرف علّة):", style = MaterialTheme.typography.labelLarge)
                RootChips(nf.variants, onRootClick)
            }
            val syns = nf.external?.synonyms.orEmpty()
            if (syns.isNotEmpty()) {
                Text("جذور قرآنية قريبة في المعنى:", style = MaterialTheme.typography.labelLarge)
                RootChips(syns, onRootClick)
                Text(
                    "مرتّبة من الأقرب — مستخرجة آليًا بمقارنة المعاني، فراجِعها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (nf.variants.isEmpty() && syns.isEmpty()) {
                Text(
                    "لا تتوفر مرادفات قرآنية لهذا الجذر في قاعدة المعاني المتاحة.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RootChips(roots: List<RootEntity>, onRootClick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        roots.forEach { r ->
            com.quranroots.app.ui.components.RootChip(
                root = r,
                label = "${r.lettersSpaced}  ·  ${r.occurrences}",
                onClick = { onRootClick(r.rootId) }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WordMatchRow(m: WordMatch, onClick: () -> Unit) {
    val onLongClick = com.quranroots.app.ui.components.rememberAddToStudy(m.root)
    Card(modifier = Modifier.fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    m.sample,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = com.quranroots.app.ui.theme.QuranFont,
                    lineHeight = 40.sp
                )
                Text(
                    "الجذر: ${m.root.lettersSpaced}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "${m.count} موضعًا",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WaznRow(w: com.quranroots.app.data.db.entities.WaznEntry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(w.text, style = MaterialTheme.typography.titleLarge,
                    fontFamily = com.quranroots.app.ui.theme.QuranFont, lineHeight = 40.sp)
                Text(
                    if (w.isLemma) "وزن الأصل" else "وزن الكلمة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "${w.words} موضعًا · ${w.roots} جذرًا" + if (w.hadith > 0) "\n${w.hadith} في الحديث" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
