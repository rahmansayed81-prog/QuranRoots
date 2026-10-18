package com.quranroots.app.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** خ-45: one data source or bundled asset, with its license and a link. */
private data class Source(
    val name: String,
    val usedFor: String,
    val license: String,
    val url: String,
    val note: String? = null
)

private val SOURCES = listOf(
    Source(
        "Tanzil — نص القرآن الكريم",
        "النص العثماني والمبسّط للآيات",
        "Creative Commons Attribution 3.0 — يُنقل النص حرفيًّا دون تعديل",
        "https://tanzil.net"
    ),
    Source(
        "Quranic Arabic Corpus",
        "التحليل الصرفي للكلمات وجذورها، ومعاني الجذور بالإنجليزية",
        "GNU GPL — يُستخدم مع ذكر المصدر والرابط",
        "https://corpus.quran.com"
    ),
    Source(
        "LK Hadith Corpus (جامعة ليدز وجامعة الملك سعود)",
        "متون صحيحي البخاري ومسلم وأسانيدها وأبوابها",
        "مدوّنة بحثية مفتوحة",
        "https://github.com/ShathaTm/LK-Hadith-Corpus",
        "Altammami, S., Atwell, E. & Alsalka, A. (2019). The Arabic–English Parallel Corpus of Authentic Hadith. IJASAT."
    ),
    Source(
        "الدرر السنية — الموسوعة الحديثية",
        "أحاديث صحيحة خارج الصحيحين بأحكامها ومصادرها، وعدد روايات الحديث في الكتب الأخرى",
        "جميع الحقوق محفوظة لمؤسسة الدرر السنية — يُنقل الحكم بنصّه مع نسبته للمحدّث",
        "https://dorar.net"
    ),
    Source(
        "Wiktionary عبر kaikki.org",
        "الجذور العربية غير القرآنية ومعانيها، وبعض الأضداد",
        "Creative Commons BY-SA",
        "https://kaikki.org"
    ),
    Source(
        "WordNet (Princeton)",
        "أضداد الكلمات الإنجليزية لاقتراح الجذور المتقابلة",
        "WordNet License",
        "https://wordnet.princeton.edu"
    ),
    Source(
        "خط Amiri Quran",
        "عرض نص الآيات",
        "SIL Open Font License 1.1",
        "https://github.com/aliftype/amiri"
    ),
    Source(
        "EveryAyah — تلاوة الحصري",
        "صوت الآيات، يُنزَّل عند الطلب ولا يُضمَّن في التطبيق",
        "حقوق التلاوة لأصحابها",
        "https://everyayah.com"
    ),
)

@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Text("المصادر والتراخيص", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Text(
                "يعتمد التطبيق على هذه المصادر، وكلٌّ منها محفوظ الحقوق لأصحابه. " +
                    "جذور الأحاديث مستخرجة بتحليل آلي وقد تقع فيها أخطاء.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(SOURCES) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.usedFor, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "الترخيص: ${s.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    s.note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { uri.openUri(s.url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(s.url.removePrefix("https://"))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = { uri.openUri("https://rahmansayed81-prog.github.io/QuranRoots/privacy.html") }) {
                    Text("سياسة الخصوصية")
                }
                TextButton(onClick = { uri.openUri("https://github.com/rahmansayed81-prog/QuranRoots") }) {
                    Text("الكود المصدري")
                }
            }
        }
    }
}
