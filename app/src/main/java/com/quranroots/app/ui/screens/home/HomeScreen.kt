package com.quranroots.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.R
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.viewmodel.HomeViewModel
import com.quranroots.app.viewmodel.RootSort

@Composable
fun HomeScreen(onRootClick: (Long) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))

    val stats by vm.stats.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val descending by vm.descending.collectAsStateWithLifecycle()
    val workspaceItems by vm.workspaceItems.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("إجمالي الجذور", stats.rootCount.toString(), Modifier.weight(1f))
                StatCard("في الورشة", workspaceItems.size.toString(), Modifier.weight(1f))
            }
        }

        if (stats.rootCount == 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.db_missing),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            item {
                Text(
                    "تصفّح الجذور (${roots.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RootSort.entries) { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = { vm.setSort(s) },
                            label = { Text(s.labelAr) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = !descending,
                            onClick = { vm.toggleDirection() },
                            leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                            label = { Text(directionLabel(sort, descending)) }
                        )
                    }
                }
            }
            items(roots, key = { it.rootId }) { root ->
                RootListRow(root, onClick = { onRootClick(root.rootId) })
            }
        }
    }
}

/** Label for the direction toggle, phrased per the active metric. */
private fun directionLabel(sort: RootSort, descending: Boolean): String = when (sort) {
    RootSort.OCCURRENCES -> if (descending) "الأكثر أولاً" else "الأقل أولاً"
    RootSort.FORMS -> if (descending) "الأكثر أولاً" else "الأقل أولاً"
    RootSort.ALPHABET -> if (descending) "أ ← ي" else "ي ← أ"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RootListRow(root: RootEntity, onClick: () -> Unit) {
    val onLongClick = com.quranroots.app.ui.components.rememberAddToStudy(root)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            root.lettersSpaced,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text("${root.occurrences} موضع", style = MaterialTheme.typography.bodySmall)
            Text("${root.formsCount} تصريف", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}
