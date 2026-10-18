# جذور القرآن — QuranRoots Android App

تطبيق أندرويد لتتبّع جذور القرآن الكريم وتصريفاتها وأماكن ورودها، يعمل أوفلاين مع تظليل الكلمة في نص الآية، ولوحة معلومات، وتشغيل صوت الحصري للآية، وبحث خارجي عن المعاني، وورشة عمل تتيح إخفاء/إظهار تصريفات وآيات بعينها ونسخ النتيجة.

## المزايا

- **بحث الجذور** بالحروف العربية (مع/بدون مسافات)؛ مثال: `ك ت ب` أو `كتب`.
- **شاشة تفاصيل الجذر** تعرض:
  - عدد المواضع وعدد التصريفات.
  - قائمة التصريفات (lemmas) — انقر للبحث في الإنترنت عن المعنى.
  - الجذور المرتبطة (التي تَرِد كثيرًا في نفس الآية).
  - كل الآيات التي ورد فيها الجذر، مع **تظليل الكلمة** المشتقّة منه.
- **تلاوة الحصري** لكل آية (تنزيل عند أول طلب من everyayah.com وتخزين محلي).
- **بحث خارجي** بنقرة على التصريف يفتح المعجم في المتصفح.
- **ورشة عمل**: أضف الجذر إلى الورشة، اضغط على أي كلمة لإخفائها/إظهارها، اضغط على تصريف لإخفاء كل آياته، ثم انسخ النتيجة كنص منسّق.
- **الحروف**: المخارج والصفات وأقوال علماء الحروف، والحروف المقطّعة.
- **علاقات الجذر**: المتجاورة، والمتقاربة دلاليًا، والمتقابلة (الأضداد)، والتقاليب.
- **في الحديث**: الجذر في الصحيحين (روايات الحديث الواحد مجمّعة)، وأحاديث صحيحة خارجهما للجذور النادرة.
- **الدراسات**: اجمع جذورًا في دراسة، مع ملاحظات ووسوم، واقتراح جذور مشتركة، ومزامنة إلى Google Drive.
- **يعمل أوفلاين** بالكامل بعد بناء قاعدة البيانات والمرّة الأولى لكل صوت.

## بناء المشروع

### 1) قاعدة البيانات (مرة واحدة)

```bash
cd data-prep
python build_db.py
```

ينزّل تلقائيًا:

| المصدر | الترخيص |
|--------|---------|
| نص قرآن عثماني/مبسّط من [Tanzil](https://tanzil.net/download/) | Tanzil license (نقل حرفي مع الإشارة للمصدر) |
| تحليل صرفي ومعاني الجذور من [Quranic Arabic Corpus](https://corpus.quran.com/) | GPL |
| جذور عربية غير قرآنية ومعانيها من [Wiktionary](https://en.wiktionary.org/) عبر [kaikki.org](https://kaikki.org/) | CC BY-SA |
| أضداد إنجليزية من [WordNet](https://wordnet.princeton.edu/) (عبر NLTK) | WordNet license |

ومصادر أخرى مضمّنة في قاعدة البيانات أو التطبيق:

| المصدر | الاستخدام |
|--------|-----------|
| متون صحيحي البخاري ومسلم من [LK Hadith Corpus](https://github.com/ShathaTm/LK-Hadith-Corpus) (Altammami, Atwell & Alsalka, 2019) | قسم «في الحديث» |
| أحاديث صحيحة خارج الصحيحين بأحكامها، وعدد روايات الحديث في الكتب الأخرى، من [الدرر السنية](https://dorar.net/) | قسم «في الحديث» (مع ذكر المصدر والحكم والمحدّث في كل حديث) |
| خط [Amiri Quran](https://github.com/aliftype/amiri) | SIL Open Font License (`assets/amiri_quran_OFL.txt`) |
| تلاوة الحصري من [everyayah.com](https://everyayah.com/) | تُنزَّل عند الطلب، لا تُضمَّن |

ثم يولّد `app/src/main/assets/quran-roots.db` (~42 MB مع الأحاديث).

> إذا فشل التنزيل، نزّل الملفات يدويًا وضعها في `data-prep/cache/` بالأسماء الموجودة في `build_db.py`.

### 2) بناء APK

افتح المشروع في Android Studio (Hedgehog أو أحدث)، ثم:

```
Build → Build APK
```

أو من سطر الأوامر بعد توليد الـ wrapper:

```bash
gradle wrapper       # مرة واحدة
./gradlew assembleDebug
```

ملف الـ APK سيكون في `app/build/outputs/apk/debug/`.

### 3) تشغيل

ثبّت الـ APK على هاتف أندرويد 8 (API 26) أو أحدث.

## بنية المشروع

```
QuranRoots/
├── data-prep/                 # سكربت بايثون لبناء قاعدة البيانات
│   ├── build_db.py
│   └── README.md
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── quran-roots.db          # تنتجه data-prep
│       ├── java/com/quranroots/app/
│       │   ├── App.kt
│       │   ├── MainActivity.kt
│       │   ├── audio/                  # AudioCache + ExoPlayer
│       │   ├── data/
│       │   │   ├── db/
│       │   │   │   ├── AppDatabase.kt          # Room (الورشة فقط)
│       │   │   │   ├── QuranDataSource.kt      # SQLite خام (للبيانات الثابتة)
│       │   │   │   ├── dao/WorkspaceDao.kt
│       │   │   │   └── entities/Entities.kt
│       │   │   └── repository/
│       │   ├── ui/
│       │   │   ├── components/AyahView.kt
│       │   │   ├── screens/{home,search,rootdetail,workspace}/
│       │   │   └── theme/
│       │   ├── util/WebSearch.kt
│       │   └── viewmodel/
│       └── res/                # الموارد (نصوص، ألوان، أيقونة)
└── README.md
```

## ملاحظات تقنية

- **قاعدتان منفصلتان**: ملف SQLite ثابت قراءة فقط للقرآن والصرف (مولّد من بايثون)، وقاعدة Room صغيرة قابلة للكتابة للورشة فقط — هذا يتجنّب فحص Room للـ identity hash على الملفات الجاهزة.
- **خط الكلمة العربية** يُترك للنظام؛ يمكنك استبداله بخط مصحف مرفق (مثل `KFGQPC Uthman Taha`) عبر `res/font/` و`Typography`.
- **الجذور المرتبطة** تُحسب بمدى ورود الجذرين معًا في نفس الآية — مفيد للاستكشاف الدلالي.
- **مكتبة المعاني**: نقر التصريف يفتح [almaany.com](https://www.almaany.com)؛ يمكن تغيير المصدر في `WebSearch.kt`.
- **الصوت**: يُنزَّل من `everyayah.com/data/Husary_128kbps/` ويُخزَّن في `cacheDir/husary/`.

## التراخيص

- كود التطبيق: MIT.
- نص القرآن (Tanzil): ترخيص Tanzil.
- التحليل الصرفي (Quranic Arabic Corpus): GPL.
- صوت الحصري (everyayah.com): راجع موقعهم.
