package com.quranroots.app.ui.screens.rootdetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quranroots.app.ui.components.LocalAddToStudy
import com.quranroots.app.ui.components.LocalContextRootId
import com.quranroots.app.ui.components.RootChip
import com.quranroots.app.ui.components.AnnotateButton
import com.quranroots.app.ui.components.AyahAnnotationsView
import com.quranroots.app.ui.components.LocalAnnotations
import com.quranroots.app.ui.components.NoteDialog
import com.quranroots.app.ui.components.TagFilterRow
import com.quranroots.app.ui.components.HadithUnitCard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.R
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.ui.components.AyahView
import com.quranroots.app.util.WebSearch
import com.quranroots.app.viewmodel.AyahOccurrence
import com.quranroots.app.viewmodel.FormInfo
import com.quranroots.app.viewmodel.RootDetailState
import com.quranroots.app.viewmodel.RootDetailViewModel
import kotlinx.coroutines.launch

@Composable
fun RootDetailScreen(
    rootId: Long,
    onBack: () -> Unit,
    onRelatedRoot: (Long) -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val vm: RootDetailViewModel = viewModel(factory = RootDetailViewModel.Factory(app))

    LaunchedEffect(rootId) { vm.load(rootId) }

    val state by vm.state.collectAsStateWithLifecycle()
    val playing by vm.audioPlayer.currentKey.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val addToStudy = LocalAddToStudy.current
    LaunchedEffect(Unit) { addToStudy?.showHintOnce(ctx) }

    // Every root chip on this screen adds to THIS root's study by context.
    CompositionLocalProvider(LocalContextRootId provides rootId) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text(
                state.root?.lettersSpaced ?: "",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (state.loading) {
            Text(stringResource(R.string.loading), modifier = Modifier.padding(16.dp))
            return@Column
        }

        val root = state.root ?: return@Column
        val canEdit = state.workspaceItemId != null
        val relTitle = stringResource(R.string.related_roots)
        val semTitle = stringResource(R.string.semantic_roots)
        val morTitle = stringResource(R.string.morpho_roots)
        val ann = LocalAnnotations.current
        var tagFilter by remember(rootId) { mutableStateOf<Long?>(null) }
        var editingRootNote by remember { mutableStateOf(false) }

        if (editingRootNote) {
            NoteDialog(
                title = "ملاحظة على الجذر ${root.lettersSpaced}",
                initial = ann.rootNotes[root.rootId].orEmpty(),
                onSave = { text ->
                    editingRootNote = false
                    scope.launch { app.workspaceRepo.setRootNote(root.rootId, text) }
                },
                onDismiss = { editingRootNote = false }
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatBlock(stringResource(R.string.occurrences), root.occurrences.toString())
                        StatBlock(stringResource(R.string.forms_count), root.formsCount.toString())
                    }
                }
            }

            item { ActionButtonsRow(state, root, ctx, scope, vm) }

            // خ-11 root note: a card when written, otherwise one quiet link.
            item {
                val note = ann.rootNotes[root.rootId]
                if (note == null) {
                    TextButton(onClick = { editingRootNote = true }) { Text("+ ملاحظة على الجذر") }
                } else {
                    Card(onClick = { editingRootNote = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("ملاحظة على الجذر", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Forms (تصريفات) — chips with occurrence counts, sorted desc.
            // Tapping a chip toggles hide-all-ayahs for that lemma.
            if (state.forms.isNotEmpty()) {
                item { Text(stringResource(R.string.forms), style = MaterialTheme.typography.titleMedium) }
                item { FormsRow(state, vm, ctx, canEdit) }
            }

            relatedSection(relTitle, state.related, state.relatedFilterRootId,
                vm::toggleRelatedFilter, onRelatedRoot)
            relatedSection(semTitle, state.semantic, state.relatedFilterRootId,
                vm::toggleRelatedFilter, onRelatedRoot)
            relatedSection(morTitle, state.morpho, state.relatedFilterRootId,
                vm::toggleRelatedFilter, onRelatedRoot)
            oppositesSection(state.opposites, state.relatedFilterRootId,
                vm::toggleRelatedFilter, onRelatedRoot)
            state.permutations?.let { fam ->
                permutationsSection(fam, state.relatedFilterRootId,
                    vm::toggleRelatedFilter, onRelatedRoot)
            }

            item { HorizontalDivider() }

            // خ-22: one list, two sources — the Quran or the Sahihayn.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.showHadith,
                        onClick = { vm.setShowHadith(false) },
                        label = { Text("الآيات (${state.occurrences.size})") }
                    )
                    FilterChip(
                        selected = state.showHadith,
                        onClick = { vm.setShowHadith(true) },
                        enabled = state.hadithWords.isNotEmpty(),
                        label = { Text("الأحاديث (${state.hadithUnitCount})") }
                    )
                }
            }

            if (state.showHadith) {
                val groups = state.hadithGroupsInView
                val byHid = state.hadithPage.associateBy { it.hid }
                val shown = groups.take(state.hadithLimit)
                val narrations = groups.sumOf { it.size }
                item {
                    Text(
                        "صحيحا البخاري ومسلم · ${groups.size} حديثًا" +
                            (if (state.relatedFilterRootId != null) " مشتركًا" else "") +
                            (if (narrations > groups.size) " في $narrations رواية" else "") +
                            " · تحليل آلي للجذور",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(shown, key = { "u${it.unitId}" }) { g ->
                    val primary = byHid[g.primary] ?: return@items
                    HadithUnitCard(
                        primary = primary,
                        others = g.others.mapNotNull { byHid[it] },
                        highlighted = { state.hadithWords[it].orEmpty() },
                        relatedHighlighted = { state.relatedHadithWords[it].orEmpty() }
                    )
                }
                if (shown.size < groups.size) {
                    item {
                        TextButton(
                            onClick = vm::loadMoreHadith,
                            enabled = !state.hadithLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("عرض المزيد (${groups.size - shown.size} متبقٍّ)") }
                    }
                }
            } else {

            // خ-12 tag filter (only appears once this root's ayahs have tags).
            item {
                TagFilterRow(
                    ayahKeys = state.occurrences.map { it.ayah.surahNo to it.ayah.ayahNo },
                    selected = tagFilter,
                    onSelect = { tagFilter = it }
                )
            }

            // Pipeline: related-root filter → tag → focus (temporary) → split
            // into visible (shown first) and hidden (one collapsed box at the end).
            var inView = state.occurrences
            tagFilter?.let { t ->
                inView = inView.filter { t in ann.ayahTags[it.ayah.surahNo to it.ayah.ayahNo].orEmpty() }
            }
            if (state.relatedFilterRootId != null) {
                inView = inView.filter { (it.ayah.surahNo to it.ayah.ayahNo) in state.relatedWordsByAyah }
            }
            val focusForm = state.focusLemma?.let { l -> state.forms.firstOrNull { it.lemma == l } }
            if (focusForm != null) {
                inView = inView.filter { (it.ayah.surahNo to it.ayah.ayahNo) in focusForm.ayahKeys }
                item { FocusBanner(focusForm.lemma, inView.size) { vm.toggleFocus(null) } }
            }
            val (hiddenOcc, visibleOcc) = inView.partition {
                (it.ayah.surahNo to it.ayah.ayahNo) in state.hiddenAyahs
            }

            items(visibleOcc, key = { "v${it.ayah.surahNo}:${it.ayah.ayahNo}" }) { occ ->
                val ayahKey = occ.ayah.surahNo to occ.ayah.ayahNo
                VisibleAyahCard(
                    occ = occ,
                    relatedHighlightedWordNos = state.relatedWordsByAyah[ayahKey].orEmpty(),
                    playing = playing,
                    canEdit = canEdit,
                    onTogglePlay = {
                        if (playing == ayahKey) vm.stopAudio()
                        else vm.playHusary(occ.ayah.surahNo, occ.ayah.ayahNo)
                    },
                    onHideAyah = { vm.toggleHiddenAyah(occ.ayah.surahNo, occ.ayah.ayahNo) },
                    onWordClick = { w -> w.lemma?.let(vm::toggleLemmaAyahs) }
                )
            }

            if (inView.isEmpty() && state.relatedFilterRootId != null) {
                item {
                    Text(
                        "لا توجد آيات مشتركة.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (visibleOcc.isEmpty() && hiddenOcc.isNotEmpty()) {
                item {
                    Text(
                        "كل الآيات هنا مخفية — افتح الصندوق بالأسفل لإظهارها.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (hiddenOcc.isNotEmpty()) {
                item(key = "hidden-box") {
                    HiddenAyahsBox(
                        hidden = hiddenOcc,
                        onShowGroup = { keys -> vm.setAyahsHidden(keys, hidden = false) },
                        onShowOne = { s, a -> vm.toggleHiddenAyah(s, a) }
                    )
                }
            }
            } // end ayah source

            if (state.audioError != null) {
                item {
                    Text(
                        "تعذّر تشغيل الصوت: ${state.audioError}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    } // CompositionLocalProvider
}

private fun androidx.compose.foundation.lazy.LazyListScope.relatedSection(
    title: String,
    roots: List<RootEntity>,
    activeFilterId: Long?,
    onToggleFilter: (Long) -> Unit,
    onOpen: (Long) -> Unit
) {
    if (roots.isEmpty()) return
    item { Text(title, style = MaterialTheme.typography.titleMedium) }
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(roots) { rel ->
                RootChip(
                    root = rel,
                    label = rel.lettersSpaced,
                    selected = activeFilterId == rel.rootId,
                    onClick = { onToggleFilter(rel.rootId) },
                    trailing = { OpenIcon { onOpen(rel.rootId) } }
                )
            }
        }
    }
}

/**
 * التقابل. Tapping a chip filters to ayahs where BOTH roots appear (the heart
 * of Quranic التقابل) with dual highlighting; the count on the chip is that
 * number of ayahs. Automatic pairs are marked «آلي» for review.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.oppositesSection(
    opposites: List<com.quranroots.app.data.db.entities.OppositeLink>,
    activeFilterId: Long?,
    onToggleFilter: (Long) -> Unit,
    onOpen: (Long) -> Unit
) {
    if (opposites.isEmpty()) return
    item {
        Column {
            Text("التقابل", style = MaterialTheme.typography.titleMedium)
            Text(
                "الرقم = آيات جمعت الجذرين. «آلي» = مستخرج آليًا فراجِعه.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(opposites) { op ->
                RootChip(
                    root = op.root,
                    label = buildString {
                        append(op.root.lettersSpaced)
                        append("  ·  ").append(op.cooccur)
                        if (op.isAutomatic) append("  ·  آلي")
                    },
                    selected = activeFilterId == op.root.rootId,
                    onClick = { onToggleFilter(op.root.rootId) },
                    trailing = { OpenIcon { onOpen(op.root.rootId) } }
                )
            }
        }
    }
}

/**
 * التقاليب (الاشتقاق الأكبر عند ابن جني). Quranic permutations behave like
 * any related root (filter to shared ayahs / open / long-press to a study);
 * non-Quranic ones are muted and show their meaning on tap.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.permutationsSection(
    fam: com.quranroots.app.data.db.entities.PermutationFamily,
    activeFilterId: Long?,
    onToggleFilter: (Long) -> Unit,
    onOpen: (Long) -> Unit
) {
    if (fam.permutations.isEmpty()) return
    val quranic = fam.permutations.count { it.root != null }
    item {
        Column {
            Text("التقاليب", style = MaterialTheme.typography.titleMedium)
            Text(
                "$quranic من ${fam.permutations.size} تقاليب قرآنية · " +
                    "${fam.multiFamilyAyahs} آية جمعت أكثر من تقليب" +
                    (fam.ownGloss?.let { " · معنى الجذر: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    item {
        var shownMeaning by remember { mutableStateOf<String?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fam.permutations, key = { it.letters }) { p ->
                    val r = p.root
                    if (r != null) {
                        RootChip(
                            root = r,
                            label = "${p.spaced}  ·  ${p.sharedAyahs}",
                            selected = activeFilterId == r.rootId,
                            onClick = {
                                onToggleFilter(r.rootId)
                                shownMeaning = p.gloss?.let { "${p.spaced}: $it" }
                            },
                            trailing = { OpenIcon { onOpen(r.rootId) } }
                        )
                    } else {
                        val arabic = p.kind == com.quranroots.app.data.db.entities.Permutation.Kind.ARABIC
                        Text(
                            p.spaced,
                            style = MaterialTheme.typography.labelLarge,
                            textDecoration = if (arabic) null
                                else androidx.compose.ui.text.style.TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (arabic) 0.85f else 0.45f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable {
                                    shownMeaning = if (arabic) "${p.spaced} (عربي غير قرآني): ${p.gloss}"
                                                   else "${p.spaced}: لم يُعثر عليه في Wiktionary — لا يعني بالضرورة أنه مهمل."
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            shownMeaning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "الرقم = آيات مشتركة مع الجذر · الباهت = عربي غير قرآني · المشطوب = لم يُعثر عليه",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** Small in-chip "open root" icon (an IconButton would make chips 48dp tall). */
@Composable
private fun OpenIcon(onClick: () -> Unit) {
    Icon(
        Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = "فتح",
        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        tint = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtonsRow(
    state: RootDetailState,
    root: RootEntity,
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    vm: RootDetailViewModel
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.workspaceItemId == null) {
            Button(onClick = vm::addToWorkspace) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                Text(
                    stringResource(R.string.add_to_workspace),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        } else {
            AssistChip(
                onClick = {},
                label = { Text("في الورشة") },
                leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) }
            )
        }
        // The current root itself → existing or new study (the long-press rule
        // covers every OTHER root on this screen).
        val addToStudy = LocalAddToStudy.current
        OutlinedButton(onClick = { addToStudy?.requestForCurrent(root) }) {
            Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
            Text("أضف إلى دراسة", modifier = Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = {
            scope.launch { copyToClipboard(ctx, buildExport(root, state)) }
        }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
            Text(stringResource(R.string.copy), modifier = Modifier.padding(start = 6.dp))
        }
        if (state.playingAll) {
            OutlinedButton(onClick = vm::stopAudio) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Text(stringResource(R.string.stop_all), modifier = Modifier.padding(start = 6.dp))
            }
        } else {
            OutlinedButton(onClick = vm::playAllOccurrences) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                Text(stringResource(R.string.play_all), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun FormsRow(
    state: RootDetailState,
    vm: RootDetailViewModel,
    ctx: Context,
    canEdit: Boolean
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.forms) { form ->
            val allHidden = form.ayahKeys.isNotEmpty() &&
                form.ayahKeys.all { it in state.hiddenAyahs }
            FilterChip(
                selected = !allHidden,
                onClick = {
                    if (canEdit) vm.toggleLemmaAyahs(form.lemma)
                    else WebSearch.openLisanArab(ctx, form.lemma)
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(form.lemma, fontFamily = com.quranroots.app.ui.theme.QuranFont)
                        Text(
                            "  ${form.occurrences}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingIcon = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // خ-16: temporary «show only this form» (not saved).
                        val focused = state.focusLemma == form.lemma
                        Icon(
                            Icons.Outlined.CenterFocusStrong,
                            contentDescription = "تركيز على هذا التصريف",
                            tint = if (focused) MaterialTheme.colorScheme.tertiary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                .clickable { vm.toggleFocus(form.lemma) }
                        )
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search_meaning),
                            modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                .clickable { WebSearch.openLisanArab(ctx, form.lemma) }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun VisibleAyahCard(
    occ: AyahOccurrence,
    relatedHighlightedWordNos: Set<Int> = emptySet(),
    playing: Pair<Int, Int>?,
    canEdit: Boolean,
    onTogglePlay: () -> Unit,
    onHideAyah: () -> Unit,
    onWordClick: (com.quranroots.app.data.db.entities.WordEntity) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text(
                "${occ.surahNameAr} : ${occ.ayah.ayahNo}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (canEdit) {
                IconButton(onClick = onHideAyah) {
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = stringResource(R.string.hide_ayah)
                    )
                }
            }
            AnnotateButton(occ.ayah.surahNo, occ.ayah.ayahNo, "${occ.surahNameAr} : ${occ.ayah.ayahNo}")
            val isCurrent = playing == (occ.ayah.surahNo to occ.ayah.ayahNo)
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (isCurrent) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_husary)
                )
            }
        }
        AyahView(
            words = occ.words,
            highlightedWordNos = occ.highlightedWordNos,
            relatedHighlightedWordNos = relatedHighlightedWordNos,
            onWordClick = if (canEdit) onWordClick else null
        )
        AyahAnnotationsView(occ.ayah.surahNo, occ.ayah.ayahNo)
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

/** Shown while focus mode is on, so the user always sees how to leave it. */
@Composable
private fun FocusBanner(lemma: String, count: Int, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null)
        Text(
            "تركيز على «$lemma» ($count آية)",
            fontFamily = com.quranroots.app.ui.theme.QuranFont,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        TextButton(onClick = onClear) { Text("إلغاء التركيز") }
    }
}

/**
 * All hidden ayahs in ONE collapsed box at the end of the list, so hiding a
 * large form never floods the screen. Expanded, they are grouped by form
 * (the lemma of the ayah's first matching word), largest group first, each
 * group restorable at once.
 */
@Composable
private fun HiddenAyahsBox(
    hidden: List<AyahOccurrence>,
    onShowGroup: (List<Pair<Int, Int>>) -> Unit,
    onShowOne: (Int, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val groups = remember(hidden) {
        hidden.groupBy { occ ->
            occ.words.firstOrNull { it.wordNo in occ.highlightedWordNos }?.lemma ?: "—"
        }.entries.sortedByDescending { it.value.size }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${hidden.size} آية مخفية",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            Text(
                if (expanded) "طيّ" else "عرض",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            groups.forEach { (lemma, occs) ->
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp)
                ) {
                    Text(
                        "$lemma  (${occs.size})",
                        fontFamily = com.quranroots.app.ui.theme.QuranFont,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        onShowGroup(occs.map { it.ayah.surahNo to it.ayah.ayahNo })
                    }) { Text("إظهار الكل") }
                }
                occs.forEach { occ ->
                    HiddenAyahStrip(occ) { onShowOne(occ.ayah.surahNo, occ.ayah.ayahNo) }
                }
            }
        }
    }
}

@Composable
private fun HiddenAyahStrip(occ: AyahOccurrence, onRestore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onRestore() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            Icons.Outlined.Visibility,
            contentDescription = stringResource(R.string.show_ayah),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            "${occ.surahNameAr} : ${occ.ayah.ayahNo}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        Text(
            stringResource(R.string.hidden_ayah_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("QuranRoots", text))
}

private fun buildExport(root: RootEntity, state: RootDetailState): String {
    val sb = StringBuilder()
    sb.append("الجذر: ").append(root.lettersSpaced).append('\n')
    sb.append("عدد المواضع: ").append(root.occurrences)
    sb.append("   عدد التصريفات: ").append(root.formsCount).append("\n\n")
    state.occurrences.forEach { occ ->
        val ayahKey = occ.ayah.surahNo to occ.ayah.ayahNo
        if (ayahKey in state.hiddenAyahs) return@forEach
        val rendered = occ.words.joinToString(" ") { it.text }
        sb.append("(${occ.surahNameAr} : ${occ.ayah.ayahNo}) $rendered\n")
    }
    return sb.toString()
}
