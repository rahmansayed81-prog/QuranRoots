package com.quranroots.app.ui.screens.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.ui.components.AyahView
import com.quranroots.app.ui.components.RootChip
import com.quranroots.app.ui.components.HadithUnitCard
import com.quranroots.app.ui.components.AnnotateButton
import com.quranroots.app.ui.components.AyahAnnotationsView
import com.quranroots.app.ui.components.LocalAnnotations
import com.quranroots.app.ui.components.NoteDialog
import com.quranroots.app.ui.components.TagFilterRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.quranroots.app.ui.theme.Highlight
import com.quranroots.app.viewmodel.StudyAyah
import com.quranroots.app.viewmodel.StudyViewModel

/**
 * خ-10: a study = several roots read together. Each root has its own color;
 * ayahs where more of the study's roots meet are listed first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyScreen(studyId: Long, onBack: () -> Unit, onRootClick: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val vm: StudyViewModel = viewModel(factory = StudyViewModel.Factory(app))
    LaunchedEffect(studyId) { vm.load(studyId) }
    val state by vm.state.collectAsStateWithLifecycle()

    val palette = if (isSystemInDarkTheme()) Highlight.StudyDark else Highlight.StudyLight
    fun colorOf(i: Int): Color = palette[i % palette.size]

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf<Long?>(null) }
    val ann = LocalAnnotations.current

    val s = state.study
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text(
                s?.study?.name ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "إعادة تسمية")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = "حذف الدراسة")
            }
        }
        if (s == null) return@Column

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                // Roots legend: color dot + chip (tap = open, × = remove).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    s.roots.forEachIndexed { i, root ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(colorOf(i)))
                            RootChip(
                                root = root,
                                label = "${root.lettersSpaced}  ·  ${root.occurrences}",
                                onClick = { onRootClick(root.rootId) },
                                trailing = {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "إزالة من الدراسة",
                                        modifier = Modifier.size(16.dp).clip(CircleShape)
                                            .clickable { vm.removeRoot(root.rootId) }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (s.roots.isEmpty())
                        "الدراسة فارغة. اضغط مطوّلًا على أي جذر في التطبيق لإضافته هنا."
                    else "لإضافة جذر: اضغط عليه مطوّلًا من أي شاشة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // خ-11: «الرابط الجامع» — the study's central conclusion.
            item {
                val link = s.study.linkNote
                Card(
                    onClick = { editingLink = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("الرابط الجامع", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            link ?: "اضغط لكتابة ما يجمع جذور هذه الدراسة…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (link == null) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            // Suggested roots: shared neighbours of ≥2 study roots.
            if (s.roots.size >= 2 && !state.loading) {
                item {
                    SuggestionsCard(
                        suggestions = state.suggestions,
                        onOpen = onRootClick,
                        onAdd = vm::addSuggestion
                    )
                }
            }
            if (s.roots.size >= 2) {
                item {
                    val shared = state.ayahs.count { it.rootIndexes.size >= 2 }
                    FilterChip(
                        selected = state.sharedOnly,
                        onClick = vm::toggleSharedOnly,
                        label = { Text("آيات اجتمع فيها أكثر من جذر ($shared)") }
                    )
                }
            }
            item {
                TagFilterRow(
                    ayahKeys = state.ayahs.map { it.ayah.surahNo to it.ayah.ayahNo },
                    selected = tagFilter,
                    onSelect = { tagFilter = it }
                )
            }
            item { HorizontalDivider() }

            // خ-23: same study, two sources.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.showHadith,
                        onClick = { vm.setShowHadith(false) },
                        label = { Text("الآيات") }
                    )
                    FilterChip(
                        selected = state.showHadith,
                        onClick = { vm.setShowHadith(true) },
                        enabled = state.hadithUnits.isNotEmpty(),
                        label = { Text("الأحاديث (${state.hadithUnits.size})") }
                    )
                }
            }

            if (state.loading) {
                item { Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp)) }
            } else if (state.showHadith) {
                val units = state.hadithUnits.filter { !state.sharedOnly || it.rootIndexes.size >= 2 }
                val byHid = state.hadithPage.associateBy { it.hid }
                val shown = units.take(state.hadithLimit)
                item {
                    Text(
                        "${units.size} حديثًا من الصحيحين" +
                            (if (s.roots.size >= 2) " · ${state.hadithUnits.count { it.rootIndexes.size >= 2 }} جمعت أكثر من جذر" else "") +
                            " · تحليل آلي للجذور",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(shown, key = { "u${it.group.unitId}" }) { u ->
                    val primary = byHid[u.group.primary] ?: return@items
                    Column {
                        Row(Modifier.padding(top = 4.dp)) {
                            u.rootIndexes.sorted().forEach { i ->
                                Box(Modifier.padding(end = 4.dp).size(10.dp).clip(CircleShape).background(colorOf(i)))
                            }
                        }
                        HadithUnitCard(
                            primary = primary,
                            others = u.group.others.mapNotNull { byHid[it] },
                            wordColors = { hid ->
                                u.refs[hid]?.rootIndexByWord?.mapValues { colorOf(it.value) }.orEmpty()
                            }
                        )
                    }
                }
                if (shown.size < units.size) {
                    item {
                        TextButton(onClick = vm::loadMoreHadith, modifier = Modifier.fillMaxWidth()) {
                            Text("عرض المزيد (${units.size - shown.size} متبقٍّ)")
                        }
                    }
                }
            } else {
                var shown = if (state.sharedOnly) state.ayahs.filter { it.rootIndexes.size >= 2 }
                            else state.ayahs
                tagFilter?.let { t ->
                    shown = shown.filter { t in ann.ayahTags[it.ayah.surahNo to it.ayah.ayahNo].orEmpty() }
                }
                val (hiddenA, visibleA) = shown.partition { state.isHidden(it) }
                item {
                    Text(
                        "${visibleA.size} آية",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(visibleA, key = { "${it.ayah.surahNo}:${it.ayah.ayahNo}" }) { a ->
                    StudyAyahCard(a, ::colorOf, onHide = { vm.setHidden(a, true) })
                }
                if (hiddenA.isNotEmpty()) {
                    item(key = "hidden-box") {
                        StudyHiddenBox(hiddenA) { vm.setHidden(it, false) }
                    }
                }
            }
        }
    }

    if (renaming && s != null) {
        var name by remember { mutableStateOf(s.study.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("إعادة تسمية الدراسة") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) {
                    Text("حفظ")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("إلغاء") } }
        )
    }
    if (editingLink && s != null) {
        NoteDialog(
            title = "الرابط الجامع — ${s.study.name}",
            initial = s.study.linkNote.orEmpty(),
            onSave = { vm.setLinkNote(it); editingLink = false },
            onDismiss = { editingLink = false }
        )
    }
    if (confirmDelete && s != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف «${s.study.name}»؟") },
            text = { Text("تُحذف الدراسة فقط؛ تبقى الجذور في الورشة كما هي.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun StudyAyahCard(a: StudyAyah, colorOf: (Int) -> Color, onHide: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                "${a.surahNameAr} : ${a.ayah.ayahNo}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // Which study roots meet in this ayah.
            a.rootIndexes.sorted().forEach { i ->
                Box(Modifier.padding(start = 4.dp).size(10.dp).clip(CircleShape).background(colorOf(i)))
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = "إخفاء الآية")
            }
            AnnotateButton(a.ayah.surahNo, a.ayah.ayahNo, "${a.surahNameAr} : ${a.ayah.ayahNo}")
        }
        AyahView(
            words = a.words,
            highlightedWordNos = emptySet(),
            wordColors = a.rootIndexByWord.mapValues { colorOf(it.value) }
        )
        AyahAnnotationsView(a.ayah.surahNo, a.ayah.ayahNo)
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

/** Collapsed by default so the study screen stays calm; header shows the count. */
@Composable
private fun SuggestionsCard(
    suggestions: List<com.quranroots.app.viewmodel.StudySuggestion>,
    onOpen: (Long) -> Unit,
    onAdd: (com.quranroots.app.data.db.entities.RootEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                if (suggestions.isEmpty()) "جذور مقترحة: لا جذور مشتركة بعد"
                else "جذور مقترحة (${suggestions.size}) — مشتركة بين جذور الدراسة",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (suggestions.isNotEmpty()) {
                Text(if (expanded) "طيّ" else "عرض", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (expanded) suggestions.forEach { sg ->
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    RootChip(
                        root = sg.root,
                        label = "${sg.root.lettersSpaced}  ·  ${sg.root.occurrences}",
                        onClick = { onOpen(sg.root.rootId) }
                    )
                    Text(
                        // e.g. «خ و ف: تقارب، تقابل · خ ش ي: تقارب»
                        sg.via.groupBy({ it.first.lettersSpaced }, { it.second })
                            .entries.joinToString(" · ") { (r, rels) -> "$r: ${rels.joinToString("، ")}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = { onAdd(sg.root) }) { Text("+ أضف") }
            }
        }
    }
}

/** Same pattern as the root screen: one collapsed box for all hidden ayahs. */
@Composable
private fun StudyHiddenBox(hidden: List<StudyAyah>, onShow: (StudyAyah) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("${hidden.size} آية مخفية", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (expanded) "طيّ" else "عرض", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) hidden.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onShow(a) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = "إظهار",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Text("${a.surahNameAr} : ${a.ayah.ayahNo}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
