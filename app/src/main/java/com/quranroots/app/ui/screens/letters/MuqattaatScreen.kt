package com.quranroots.app.ui.screens.letters

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quranroots.app.App
import com.quranroots.app.data.lore.MuqattaaSurah

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuqattaatScreen(
    onBack: () -> Unit,
    onLetterClick: (String) -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val lore = app.lettersLore
    val mq = lore.muqattaat

    // Surah names for display.
    val surahNames by produceState(initialValue = emptyMap<Int, String>()) {
        value = mq.surahs.associate { it.surah to (app.quranRepo.surah(it.surah)?.nameAr ?: it.surah.toString()) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text(
                mq.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(mq.intro, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            }
            item {
                Text("الحروف النورانية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mq.letters.forEach { ch ->
                        SuggestionChip(
                            onClick = { onLetterClick(ch) },
                            label = { Text(ch, style = MaterialTheme.typography.titleLarge) }
                        )
                    }
                }
            }
            item {
                Text("السور المفتتَحة بها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            items(mq.surahs) { s ->
                SurahRow(s, surahNames[s.surah] ?: s.surah.toString())
            }
        }
    }
}

@Composable
private fun SurahRow(s: MuqattaaSurah, name: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                s.opening,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "سورة $name",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp).weight(1f)
            )
            Text("(${s.surah})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
