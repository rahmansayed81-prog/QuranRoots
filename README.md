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
| نص قرآن عثماني/مبسّط من [Tanzil](https://tanzil.net/download/) | Tanzil license |
| تحليل صرفي من [Quranic Arabic Corpus](https://corpus.quran.com/download/) | GPL |

ثم يولّد `app/src/main/assets/quran-roots.db` (~10–15 MB).

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
