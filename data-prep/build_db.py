"""
Build the offline SQLite database for the QuranRoots Android app.

Inputs (downloaded automatically into ./cache on first run):
  1. Tanzil Quran text (Uthmani + simple) — public domain / Tanzil license.
     https://tanzil.net/download/   (mirrored copies are bundled by id below)
  2. Quranic Arabic Corpus morphology v0.4 — GNU GPL.
     https://corpus.quran.com/download/

Output:
  ../app/src/main/assets/quran-roots.db

The script is idempotent: re-running rebuilds the DB from cached files.
"""

from __future__ import annotations

import math
import os
import re
import sqlite3
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"
DB_PATH = ASSETS / "quran-roots.db"

CACHE.mkdir(exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)

LOC_RE = re.compile(r"\((\d+):(\d+):(\d+):(\d+)\)")

# Known-working mirrors. Each file has a small validator function that ensures
# the downloaded content is actually the expected dataset (rejects HTML error
# pages from misconfigured form endpoints).
DOWNLOADS = {
    "quran-uthmani.txt": [
        "https://raw.githubusercontent.com/risan/quran-json/main/source/text/quran-uthmani.txt",
        "https://raw.githubusercontent.com/spa5k/quran_data/main/text/uthmani.txt",
    ],
    "quran-simple.txt": [
        "https://raw.githubusercontent.com/risan/quran-json/main/source/text/quran-simple.txt",
        "https://raw.githubusercontent.com/spa5k/quran_data/main/text/simple.txt",
    ],
    "quranic-corpus-morphology-0.4.txt": [
        "https://raw.githubusercontent.com/cltk/arabic_morphology_quranic-corpus/master/quranic-corpus-morphology-0.4.txt",
        "https://raw.githubusercontent.com/bnjasim/quranic-corpus/master/quranic-corpus-morphology-0.4.txt",
        "https://raw.githubusercontent.com/alstat/QuranTree.jl/master/data/quranic-corpus-morphology-0.4.txt",
    ],
    "en.sahih.txt": [
        "https://raw.githubusercontent.com/risan/quran-json/main/source/translation/en.sahih.txt",
        "https://raw.githubusercontent.com/semarketir/quranjson/master/source/translation/en.sahih.txt",
        "https://raw.githubusercontent.com/spa5k/quran_data/main/translations/en/en.sahih.txt",
    ],
}


def _is_html(text: str) -> bool:
    head = text[:512].lstrip().lower()
    return head.startswith("<!doctype") or head.startswith("<html") or "<head" in head[:200]


_ARABIC_RANGE = re.compile(r"[؀-ۿ]")


def validate_quran_text(content: str) -> bool:
    if _is_html(content):
        return False
    # Accept Tanzil pipe format ("1|1|بسم..."), sequential format
    # ("1\tبسم..."), or plain-text ayah-per-line format. The criterion is:
    # at least the first ayah of Al-Fatiha appears within the first ~10 data
    # lines.
    data_lines = []
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        data_lines.append(line)
        if len(data_lines) >= 10:
            break
    if not data_lines:
        return False
    # Must contain Arabic characters and have a plausibly-large count.
    return any(_ARABIC_RANGE.search(line) for line in data_lines) and \
        len(content.splitlines()) >= 1000


def validate_corpus(content: str) -> bool:
    if _is_html(content):
        return False
    # Find at least one location-tagged data row.
    for line in content.splitlines()[:200]:
        if LOC_RE.match(line):
            return True
    return False


def validate_en_translation(content: str) -> bool:
    if _is_html(content):
        return False
    # Look for typical Sahih English text.
    lower = content[:5000].lower()
    return ("allah" in lower or "god" in lower) and len(content.splitlines()) >= 1000


VALIDATORS = {
    "quran-uthmani.txt": validate_quran_text,
    "quran-simple.txt": validate_quran_text,
    "quranic-corpus-morphology-0.4.txt": validate_corpus,
    "en.sahih.txt": validate_en_translation,
}

# ---------------------------------------------------------------------------
# Buckwalter -> Arabic transliteration
# ---------------------------------------------------------------------------

BW2AR = {
    "'": "ء",  # hamza
    "|": "آ",  # alif madda
    ">": "أ",  # alif hamza above
    "&": "ؤ",  # waw hamza
    "<": "إ",  # alif hamza below
    "}": "ئ",  # ya hamza
    "A": "ا",  # alif
    "b": "ب",
    "p": "ة",  # ta marbuta
    "t": "ت",
    "v": "ث",  # tha
    "j": "ج",
    "H": "ح",
    "x": "خ",
    "d": "د",
    "*": "ذ",  # dhal
    "r": "ر",
    "z": "ز",
    "s": "س",
    "$": "ش",  # shin
    "S": "ص",
    "D": "ض",
    "T": "ط",
    "Z": "ظ",
    "E": "ع",  # ain
    "g": "غ",
    "_": "ـ",  # tatweel
    "f": "ف",
    "q": "ق",
    "k": "ك",
    "l": "ل",
    "m": "م",
    "n": "ن",
    "h": "ه",
    "w": "و",
    "Y": "ى",  # alif maqsura
    "y": "ي",
    "F": "ً",
    "N": "ٌ",
    "K": "ٍ",
    "a": "َ",
    "u": "ُ",
    "i": "ِ",
    "~": "ّ",  # shadda
    "o": "ْ",  # sukun
    "`": "ٰ",  # alif khanjariyya
    "{": "ٱ",  # alif wasla
}


# Symbols used in QAC's *extended* Buckwalter for Quran-specific typographic
# marks (e.g. small high marks, alif khanjariyya placement hints). They have
# no semantic meaning for our purposes — drop them to keep lemmas clean.
_BW_DROP = set("@^#")


def clean_lemma(ar: str) -> str | None:
    """QAC lemmas carry non-letter residue after Buckwalter conversion: sense
    indices (maE2 → مَع2), and extended marks it has no Arabic mapping for
    ('[', '.', ','). Keep Arabic letters, harakat and dagger alif only."""
    kept = "".join(ch for ch in ar
                   if 0x0621 <= ord(ch) <= 0x0652 or ord(ch) in (0x0670, 0x0671))
    return kept or None


def buckwalter_to_arabic(bw: str) -> str:
    return "".join(BW2AR.get(ch, ch) for ch in bw if ch not in _BW_DROP)


# Quranic recitation/stop marks (U+06D6 to U+06ED) appear as standalone tokens
# in the Tanzil text (e.g. ۛ between two words). They are not real words —
# they're recitation guidance that visually floats between letters.
def _is_real_word(token: str) -> bool:
    for ch in token:
        cp = ord(ch)
        if 0x0621 <= cp <= 0x063A or 0x0641 <= cp <= 0x064A or cp == 0x0671:
            return True
    return False


def clean_tanzil_words(text: str) -> list[str]:
    """Split Tanzil text into ayah-words, attaching standalone Quranic marks
    (ۛ ۖ ۗ etc.) to the preceding word so word counts match the Quranic
    Arabic Corpus's word numbering."""
    raw = text.split()
    out: list[str] = []
    for tok in raw:
        if _is_real_word(tok):
            out.append(tok)
        elif out:
            out[-1] = out[-1] + " " + tok
        else:
            out.append(tok)
    return out


# ---------------------------------------------------------------------------
# Download helpers
# ---------------------------------------------------------------------------

def fetch(name: str) -> Path:
    target = CACHE / name
    validator = VALIDATORS[name]

    # If a cached file exists, validate it. If it's HTML (or otherwise
    # malformed), discard and re-download.
    if target.exists() and target.stat().st_size > 0:
        try:
            content = target.read_text(encoding="utf-8", errors="ignore")
            if validator(content):
                return target
            print(f"  cached {name} is invalid (likely HTML error page) — re-downloading.")
            target.unlink()
        except Exception:
            target.unlink(missing_ok=True)

    for url in DOWNLOADS[name]:
        try:
            print(f"Downloading {name} from {url[:80]}…")
            req = urllib.request.Request(url, headers={"User-Agent": "QuranRoots/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
            if not data:
                continue
            text = data.decode("utf-8", errors="ignore")
            if not validator(text):
                print(f"  rejected (looks like HTML or wrong format).")
                continue
            target.write_bytes(data)
            return target
        except Exception as e:
            print(f"  failed: {e}")
            continue

    raise SystemExit(
        f"\nCould not download a valid copy of {name}.\n"
        f"Please download it manually and place it at:\n"
        f"  {target}\n"
        f"Sources to try:\n  " + "\n  ".join(DOWNLOADS[name]) + "\nThen re-run this script."
    )


# ---------------------------------------------------------------------------
# Parse Tanzil text (one line per ayah: "surah|ayah|text")
# ---------------------------------------------------------------------------

def parse_tanzil(path: Path) -> dict[tuple[int, int], str]:
    """Accept any of three formats:

      1. Official Tanzil pipe format:  ``surah|ayah|text``
      2. Sequential numbered format:   ``index\\ttext``  (1..6236)
      3. Plain-text format:            one ayah per line, no numbering
         (this is what most GitHub Tanzil mirrors ship)
    """
    out: dict[tuple[int, int], str] = {}
    sequential: list[str] = []  # for formats 2 & 3

    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # Format 1: surah|ayah|text
            if "|" in line:
                parts = line.split("|", 2)
                if len(parts) == 3:
                    try:
                        s = int(parts[0]); a = int(parts[1])
                        out[(s, a)] = parts[2]
                        continue
                    except ValueError:
                        pass
            # Format 2: index<TAB>text
            if "\t" in line:
                idx_str, _, text = line.partition("\t")
                if idx_str.strip().isdigit():
                    sequential.append(text)
                    continue
            # Format 3: plain text
            sequential.append(line)

    if not out and sequential:
        ordering: list[tuple[int, int]] = []
        for surah_no, _name, _tr, count in SURAH_META:
            for ayah_no in range(1, count + 1):
                ordering.append((surah_no, ayah_no))
        if len(ordering) != 6236:
            raise SystemExit(f"Internal error: SURAH_META totals {len(ordering)}, expected 6236.")
        if len(sequential) < 6236:
            raise SystemExit(
                f"Quran text has only {len(sequential)} ayahs; expected at least 6236.\n"
                f"The downloaded file may be incomplete."
            )
        # Take only the first 6236 lines (some mirrors append extra metadata
        # or licence text after the data).
        for i in range(6236):
            s, a = ordering[i]
            out[(s, a)] = sequential[i]

    return out


# ---------------------------------------------------------------------------
# Parse Quranic Arabic Corpus morphology
# Each non-comment row:
#   LOCATION FORM  TAG  FEATURES
#   (1:1:1:1) bisomi STEM POS:N|... |LEM:som|ROOT:smw|...
# ---------------------------------------------------------------------------

def parse_corpus(path: Path):
    """Yield (surah, ayah, word, segment, form_bw, features_dict)."""
    with path.open("r", encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 4:
                continue
            loc_match = LOC_RE.match(cols[0])
            if not loc_match:
                continue
            surah, ayah, word, seg = (int(x) for x in loc_match.groups())
            form_bw = cols[1]
            tag = cols[2]
            features = {}
            for kv in cols[3].split("|"):
                if ":" in kv:
                    k, v = kv.split(":", 1)
                    features[k] = v
                else:
                    features.setdefault(kv, "")
            features["_TAG"] = tag
            yield surah, ayah, word, seg, form_bw, features


# ---------------------------------------------------------------------------
# Surah metadata (114 surahs, name + ayah count)
# ---------------------------------------------------------------------------

SURAH_META = [
    (1, "الفاتحة", "Al-Fatihah", 7),
    (2, "البقرة", "Al-Baqarah", 286),
    (3, "آل عمران", "Aal-E-Imran", 200),
    (4, "النساء", "An-Nisa", 176),
    (5, "المائدة", "Al-Maidah", 120),
    (6, "الأنعام", "Al-Anam", 165),
    (7, "الأعراف", "Al-Araf", 206),
    (8, "الأنفال", "Al-Anfal", 75),
    (9, "التوبة", "At-Tawbah", 129),
    (10, "يونس", "Yunus", 109),
    (11, "هود", "Hud", 123),
    (12, "يوسف", "Yusuf", 111),
    (13, "الرعد", "Ar-Rad", 43),
    (14, "إبراهيم", "Ibrahim", 52),
    (15, "الحجر", "Al-Hijr", 99),
    (16, "النحل", "An-Nahl", 128),
    (17, "الإسراء", "Al-Isra", 111),
    (18, "الكهف", "Al-Kahf", 110),
    (19, "مريم", "Maryam", 98),
    (20, "طه", "Ta-Ha", 135),
    (21, "الأنبياء", "Al-Anbiya", 112),
    (22, "الحج", "Al-Hajj", 78),
    (23, "المؤمنون", "Al-Muminun", 118),
    (24, "النور", "An-Nur", 64),
    (25, "الفرقان", "Al-Furqan", 77),
    (26, "الشعراء", "Ash-Shuara", 227),
    (27, "النمل", "An-Naml", 93),
    (28, "القصص", "Al-Qasas", 88),
    (29, "العنكبوت", "Al-Ankabut", 69),
    (30, "الروم", "Ar-Rum", 60),
    (31, "لقمان", "Luqman", 34),
    (32, "السجدة", "As-Sajdah", 30),
    (33, "الأحزاب", "Al-Ahzab", 73),
    (34, "سبأ", "Saba", 54),
    (35, "فاطر", "Fatir", 45),
    (36, "يس", "Ya-Sin", 83),
    (37, "الصافات", "As-Saffat", 182),
    (38, "ص", "Sad", 88),
    (39, "الزمر", "Az-Zumar", 75),
    (40, "غافر", "Ghafir", 85),
    (41, "فصلت", "Fussilat", 54),
    (42, "الشورى", "Ash-Shuraa", 53),
    (43, "الزخرف", "Az-Zukhruf", 89),
    (44, "الدخان", "Ad-Dukhan", 59),
    (45, "الجاثية", "Al-Jathiyah", 37),
    (46, "الأحقاف", "Al-Ahqaf", 35),
    (47, "محمد", "Muhammad", 38),
    (48, "الفتح", "Al-Fath", 29),
    (49, "الحجرات", "Al-Hujurat", 18),
    (50, "ق", "Qaf", 45),
    (51, "الذاريات", "Adh-Dhariyat", 60),
    (52, "الطور", "At-Tur", 49),
    (53, "النجم", "An-Najm", 62),
    (54, "القمر", "Al-Qamar", 55),
    (55, "الرحمن", "Ar-Rahman", 78),
    (56, "الواقعة", "Al-Waqiah", 96),
    (57, "الحديد", "Al-Hadid", 29),
    (58, "المجادلة", "Al-Mujadila", 22),
    (59, "الحشر", "Al-Hashr", 24),
    (60, "الممتحنة", "Al-Mumtahanah", 13),
    (61, "الصف", "As-Saff", 14),
    (62, "الجمعة", "Al-Jumuah", 11),
    (63, "المنافقون", "Al-Munafiqun", 11),
    (64, "التغابن", "At-Taghabun", 18),
    (65, "الطلاق", "At-Talaq", 12),
    (66, "التحريم", "At-Tahrim", 12),
    (67, "الملك", "Al-Mulk", 30),
    (68, "القلم", "Al-Qalam", 52),
    (69, "الحاقة", "Al-Haqqah", 52),
    (70, "المعارج", "Al-Maarij", 44),
    (71, "نوح", "Nuh", 28),
    (72, "الجن", "Al-Jinn", 28),
    (73, "المزمل", "Al-Muzzammil", 20),
    (74, "المدثر", "Al-Muddaththir", 56),
    (75, "القيامة", "Al-Qiyamah", 40),
    (76, "الإنسان", "Al-Insan", 31),
    (77, "المرسلات", "Al-Mursalat", 50),
    (78, "النبأ", "An-Naba", 40),
    (79, "النازعات", "An-Naziat", 46),
    (80, "عبس", "Abasa", 42),
    (81, "التكوير", "At-Takwir", 29),
    (82, "الانفطار", "Al-Infitar", 19),
    (83, "المطففين", "Al-Mutaffifin", 36),
    (84, "الانشقاق", "Al-Inshiqaq", 25),
    (85, "البروج", "Al-Buruj", 22),
    (86, "الطارق", "At-Tariq", 17),
    (87, "الأعلى", "Al-Ala", 19),
    (88, "الغاشية", "Al-Ghashiyah", 26),
    (89, "الفجر", "Al-Fajr", 30),
    (90, "البلد", "Al-Balad", 20),
    (91, "الشمس", "Ash-Shams", 15),
    (92, "الليل", "Al-Layl", 21),
    (93, "الضحى", "Ad-Duhaa", 11),
    (94, "الشرح", "Ash-Sharh", 8),
    (95, "التين", "At-Tin", 8),
    (96, "العلق", "Al-Alaq", 19),
    (97, "القدر", "Al-Qadr", 5),
    (98, "البينة", "Al-Bayyinah", 8),
    (99, "الزلزلة", "Az-Zalzalah", 8),
    (100, "العاديات", "Al-Adiyat", 11),
    (101, "القارعة", "Al-Qariah", 11),
    (102, "التكاثر", "At-Takathur", 8),
    (103, "العصر", "Al-Asr", 3),
    (104, "الهمزة", "Al-Humazah", 9),
    (105, "الفيل", "Al-Fil", 5),
    (106, "قريش", "Quraysh", 4),
    (107, "الماعون", "Al-Maun", 7),
    (108, "الكوثر", "Al-Kawthar", 3),
    (109, "الكافرون", "Al-Kafirun", 6),
    (110, "النصر", "An-Nasr", 3),
    (111, "المسد", "Al-Masad", 5),
    (112, "الإخلاص", "Al-Ikhlas", 4),
    (113, "الفلق", "Al-Falaq", 5),
    (114, "الناس", "An-Nas", 6),
]


# ---------------------------------------------------------------------------
# Build database
# ---------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS surahs (
    surah_no INTEGER PRIMARY KEY,
    name_ar TEXT NOT NULL,
    name_translit TEXT NOT NULL,
    ayah_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ayahs (
    surah_no INTEGER NOT NULL,
    ayah_no INTEGER NOT NULL,
    text_uthmani TEXT NOT NULL,
    text_simple TEXT NOT NULL,
    PRIMARY KEY (surah_no, ayah_no)
);
CREATE INDEX IF NOT EXISTS index_ayahs_surah_no ON ayahs(surah_no);

CREATE TABLE IF NOT EXISTS roots (
    root_id INTEGER PRIMARY KEY AUTOINCREMENT,
    letters TEXT NOT NULL,
    letters_spaced TEXT NOT NULL,
    occurrences INTEGER NOT NULL,
    forms_count INTEGER NOT NULL,
    meaning_ar TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS index_roots_letters ON roots(letters);

CREATE TABLE IF NOT EXISTS words (
    surah_no INTEGER NOT NULL,
    ayah_no INTEGER NOT NULL,
    word_no INTEGER NOT NULL,
    text TEXT NOT NULL,
    lemma TEXT,
    root_id INTEGER,
    PRIMARY KEY (surah_no, ayah_no, word_no)
);
CREATE INDEX IF NOT EXISTS index_words_root_id ON words(root_id);
CREATE INDEX IF NOT EXISTS index_words_surah_no_ayah_no ON words(surah_no, ayah_no);

-- Search index: normalized (diacritic-stripped) word/lemma forms → root.
-- Lets a user type a word they don't know the root of and land on the root.
CREATE TABLE IF NOT EXISTS word_forms (
    form_plain TEXT NOT NULL,   -- normalized surface/lemma form (no diacritics)
    root_id INTEGER NOT NULL,
    sample TEXT NOT NULL,       -- representative diacritized form to display
    count INTEGER NOT NULL,     -- total occurrences backing this (form, root)
    PRIMARY KEY (form_plain, root_id)
);
CREATE INDEX IF NOT EXISTS index_word_forms_form ON word_forms(form_plain);

CREATE TABLE IF NOT EXISTS root_relations (
    root_id INTEGER NOT NULL,
    related_root_id INTEGER NOT NULL,
    score INTEGER NOT NULL,
    PRIMARY KEY (root_id, related_root_id)
);
CREATE INDEX IF NOT EXISTS index_root_relations_related_root_id
    ON root_relations(related_root_id);

-- Semantic neighbors: cosine similarity over per-root English gloss bags (Sahih
-- International), TF-IDF weighted. Score = int(cosine * 10000).
CREATE TABLE IF NOT EXISTS root_semantic (
    root_id INTEGER NOT NULL,
    related_root_id INTEGER NOT NULL,
    score INTEGER NOT NULL,
    PRIMARY KEY (root_id, related_root_id)
);
CREATE INDEX IF NOT EXISTS index_root_semantic_related
    ON root_semantic(related_root_id);

-- Morphological neighbors: roots sharing ≥2 letters (with positional bonus).
-- Score combines overlap count and same-position matches.
CREATE TABLE IF NOT EXISTS root_morpho (
    root_id INTEGER NOT NULL,
    related_root_id INTEGER NOT NULL,
    score INTEGER NOT NULL,
    PRIMARY KEY (root_id, related_root_id)
);
CREATE INDEX IF NOT EXISTS index_root_morpho_related
    ON root_morpho(related_root_id);

-- Per-letter statistics for the "Letters" feature. All derived from our own
-- corpus + a curated abjad/makhraj table; no external data.
-- Roots absent from the Quran (from Wiktionary) and their nearest Quranic
-- roots by meaning. Built from cache/external_synonyms.json when present.
CREATE TABLE IF NOT EXISTS external_roots (
    letters TEXT PRIMARY KEY,     -- normalized root letters (hamza → ا)
    gloss TEXT NOT NULL           -- short English meaning (Wiktionary)
);
CREATE TABLE IF NOT EXISTS external_synonyms (
    ext_letters TEXT NOT NULL,
    root_id INTEGER NOT NULL,     -- Quranic root
    score INTEGER NOT NULL,       -- cosine × 10000
    PRIMARY KEY (ext_letters, root_id)
);

-- Short English meaning per Quranic root (for التقاليب display).
-- source: qac (QAC dictionary heading) | wiktionary (fallback).
CREATE TABLE IF NOT EXISTS root_glosses (
    root_id INTEGER PRIMARY KEY,
    gloss TEXT NOT NULL,
    source TEXT NOT NULL
);

-- التقابل: opposite root pairs (stored in both directions).
-- source: curated | wiktionary | wordnet (the last two are automatic).
CREATE TABLE IF NOT EXISTS root_opposites (
    root_id INTEGER NOT NULL,
    opposite_root_id INTEGER NOT NULL,
    source TEXT NOT NULL,
    cooccur INTEGER NOT NULL,     -- ayahs containing both roots
    label TEXT NOT NULL,          -- e.g. «الليل والنهار» or «night ↔ day»
    PRIMARY KEY (root_id, opposite_root_id)
);

CREATE TABLE IF NOT EXISTS letters (
    letter TEXT PRIMARY KEY,          -- single Arabic letter (28 base letters)
    hija_order INTEGER NOT NULL,      -- ا ب ت ث … order (1..28)
    abjad_order INTEGER NOT NULL,     -- ا ب ج د … order (1..28)
    abjad_value INTEGER NOT NULL,     -- numeric value (1,2,3,4 … 1000)
    freq_simple INTEGER NOT NULL,     -- letter count in simple (imlaa'i) text
    freq_uthmani INTEGER NOT NULL,    -- letter count in Uthmani text
    root_count INTEGER NOT NULL,      -- # distinct roots containing the letter
    weighted_occ INTEGER NOT NULL,    -- Σ occurrences of those roots
    is_muqattaa INTEGER NOT NULL,     -- 1 if among the 14 fawatih letters
    makhraj_group TEXT NOT NULL       -- key into the lore JSON glossary
);
"""


# ---------------------------------------------------------------------------
# English stopwords (curated short list — Sahih is small enough that this is
# enough; words that survive will be discriminative).
# ---------------------------------------------------------------------------
_EN_STOPWORDS = set("""
a about above after again against all also am an and any are aren as at
be been being below between both but by can cannot could did do does doing don
down during each etc even ever every few for from further had has have
having he her here hers herself him himself his how i if in indeed into is
isn it its itself just like may me might more most much must my myself
no nor not now of off on once only or other our ours ourselves out over own
rather said same say see shall she should since so some such than that
the their theirs them themselves then there these they this those though through to
too under until up upon us very was way we were what when where which
while who whom whose why will with within without would yet you your yours
yourself yourselves toward towards verily indeed thus thereof unto whereas
o ye thy thee shalt thou hath wherein whereby whereto wherever whatsoever
ones two three four five six seven eight nine ten any anyone anything anybody
who's i've i'm i'd i'll he's she's it's that's there's they're we're
""".split())


_WORD_RE = re.compile(r"[A-Za-z']+")


def tokenize_english(text: str) -> list[str]:
    out = []
    for raw in _WORD_RE.findall(text.lower()):
        w = raw.strip("'")
        if len(w) < 3:
            continue
        if w in _EN_STOPWORDS:
            continue
        out.append(w)
    return out


def main() -> int:
    print("[1/5] Fetching source data…")
    uthmani_path = fetch("quran-uthmani.txt")
    simple_path = fetch("quran-simple.txt")
    corpus_path = fetch("quranic-corpus-morphology-0.4.txt")
    try:
        sahih_path = fetch("en.sahih.txt")
    except SystemExit as e:
        print(f"  (warning) skipping semantic neighbors: {e}")
        sahih_path = None

    print("[2/5] Parsing Quran text…")
    uthmani = parse_tanzil(uthmani_path)
    simple = parse_tanzil(simple_path)
    if not uthmani:
        raise SystemExit("Uthmani text empty after parse — check the source file.")

    print("[3/5] Parsing morphology corpus…")
    # Per-word data: collect all segments, derive (form, root, lemma) per word.
    word_segments: dict[tuple[int, int, int], list] = defaultdict(list)
    for row in parse_corpus(corpus_path):
        s, a, w, seg, form_bw, feats = row
        word_segments[(s, a, w)].append((seg, form_bw, feats))

    # For each word: concat all segment forms (Buckwalter -> Arabic), pick the
    # STEM segment's root + lemma when present.
    word_records: dict[tuple[int, int, int], tuple[str, str | None, str | None]] = {}
    for key, segs in word_segments.items():
        segs.sort(key=lambda x: x[0])
        full_bw = "".join(s[1] for s in segs)
        text = buckwalter_to_arabic(full_bw)
        root_bw = lemma_bw = None
        for _seg, _form, feats in segs:
            if "ROOT" in feats and root_bw is None:
                root_bw = feats["ROOT"]
            if "LEM" in feats and lemma_bw is None:
                lemma_bw = feats["LEM"]
        root_ar = buckwalter_to_arabic(root_bw) if root_bw else None
        lemma_ar = clean_lemma(buckwalter_to_arabic(lemma_bw)) if lemma_bw else None
        word_records[key] = (text, lemma_ar, root_ar)

    print(f"      parsed {len(word_records):,} words.")

    # Aggregate root statistics + co-occurrences.
    root_occurrences: Counter = Counter()
    root_forms: defaultdict[str, set[str]] = defaultdict(set)
    ayah_roots: defaultdict[tuple[int, int], set[str]] = defaultdict(set)
    for (s, a, _w), (text, lemma, root) in word_records.items():
        if root:
            root_occurrences[root] += 1
            root_forms[root].add(lemma or text)
            ayah_roots[(s, a)].add(root)

    print(f"      identified {len(root_occurrences):,} unique roots.")

    print("[4/5] Building SQLite database…")
    if DB_PATH.exists():
        DB_PATH.unlink()
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(SCHEMA)
    cur = conn.cursor()

    # surahs
    cur.executemany(
        "INSERT INTO surahs (surah_no, name_ar, name_translit, ayah_count) VALUES (?,?,?,?)",
        SURAH_META,
    )

    # ayahs
    # Tanzil prepends the basmala to ayah 1 of every surah except 1 (where it IS
    # ayah 1) and 9 (none). In the mushaf it is not part of that ayah, so strip
    # it from the stored text (the words table below drops it the same way).
    def drop_basmala(s: int, a: int, text: str, uthmani_style: bool) -> str:
        if a != 1 or s in (1, 9):
            return text
        toks = clean_tanzil_words(text) if uthmani_style else text.split()
        return " ".join(toks[4:])

    ayah_rows = []
    for (s, a), uthm in sorted(uthmani.items()):
        simp = simple.get((s, a), strip_diacritics(uthm))
        ayah_rows.append((s, a, drop_basmala(s, a, uthm, True), drop_basmala(s, a, simp, False)))
    cur.executemany(
        "INSERT INTO ayahs (surah_no, ayah_no, text_uthmani, text_simple) VALUES (?,?,?,?)",
        ayah_rows,
    )

    # roots
    root_id_by_letters: dict[str, int] = {}
    for letters, occ in sorted(root_occurrences.items(), key=lambda kv: -kv[1]):
        spaced = " ".join(letters)
        forms_count = len(root_forms[letters])
        cur.execute(
            "INSERT INTO roots (letters, letters_spaced, occurrences, forms_count, meaning_ar) "
            "VALUES (?,?,?,?,?)",
            (letters, spaced, occ, forms_count, None),
        )
        root_id_by_letters[letters] = cur.lastrowid

    # words: build the table from the *Tanzil* word split (so the visible text
    # is in proper Quranic Unicode) and align corpus root/lemma data by word
    # position, accounting for the bismillah prefix that Tanzil prepends to
    # the first ayah of every surah except 1 (where it IS the ayah) and 9.
    BISMILLAH_PREFIX_WORDS = 4  # "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ"

    def has_basmala_prefix(s: int, a: int) -> bool:
        return a == 1 and s not in (1, 9)

    # Index corpus data by (surah, ayah) -> {corpus_word_no: (lemma, root)}
    corpus_by_ayah: defaultdict[tuple[int, int], dict[int, tuple]] = defaultdict(dict)
    for (s, a, w), (_text, lemma, root) in word_records.items():
        corpus_by_ayah[(s, a)][w] = (lemma, root)

    word_rows = []
    align_warnings = 0
    for (s, a), tanzil_text in uthmani.items():
        tanzil_words = clean_tanzil_words(tanzil_text)
        offset = BISMILLAH_PREFIX_WORDS if has_basmala_prefix(s, a) else 0
        # Sanity check: corpus's max word_no should align with tanzil count.
        corpus_words_for_ayah = corpus_by_ayah.get((s, a), {})
        expected_corpus_count = len(tanzil_words) - offset
        actual_corpus_count = len(corpus_words_for_ayah)
        if corpus_words_for_ayah and actual_corpus_count != expected_corpus_count:
            align_warnings += 1
            if align_warnings <= 3:
                print(f"      align warn ({s}:{a}): tanzil={len(tanzil_words)} - offset={offset}"
                      f" != corpus_words={actual_corpus_count}")
        for i, tword in enumerate(tanzil_words):
            tanzil_word_no = i + 1
            corpus_no = tanzil_word_no - offset
            if corpus_no < 1:
                continue  # basmala prefix — not part of the ayah (see drop_basmala)
            lemma = root = None
            if corpus_no >= 1:
                pair = corpus_words_for_ayah.get(corpus_no)
                if pair is not None:
                    lemma, root = pair
            rid = root_id_by_letters.get(root) if root else None
            # Word numbers now follow the corpus (1 = first word of the ayah).
            word_rows.append((s, a, corpus_no, tword, lemma, rid))
    if align_warnings > 3:
        print(f"      ... and {align_warnings - 3} more alignment warnings.")
    cur.executemany(
        "INSERT INTO words (surah_no, ayah_no, word_no, text, lemma, root_id) VALUES (?,?,?,?,?,?)",
        word_rows,
    )

    # word_forms: normalized search index (surface forms + lemmas → root).
    # Each form is indexed under several normalized variants so imlaa'i typing
    # matches Uthmani rasm (dagger alif, waw/ya-written long-a). (form, root) ->
    # {surface: count}
    form_map: defaultdict[tuple[str, int], Counter] = defaultdict(Counter)
    # count = distinct word positions. A word whose surface and lemma
    # normalize to the same form must count once, not twice.
    form_pos: defaultdict[tuple[str, int], set] = defaultdict(set)
    for (s, a, wno, text, lemma, rid) in word_rows:
        if rid is None:
            continue
        for variant in search_variants(text):
            form_map[(variant, rid)][text] += 1
            form_pos[(variant, rid)].add((s, a, wno))
        if lemma:
            for variant in search_variants(lemma):
                form_map[(variant, rid)][lemma] += 1
                form_pos[(variant, rid)].add((s, a, wno))
    wf_rows = []
    for (form_plain, rid), surfaces in form_map.items():
        sample, _ = surfaces.most_common(1)[0]
        wf_rows.append((form_plain, rid, sample, len(form_pos[(form_plain, rid)])))
    cur.executemany(
        "INSERT INTO word_forms (form_plain, root_id, sample, count) VALUES (?,?,?,?)",
        wf_rows,
    )
    print(f"      {len(wf_rows):,} searchable word forms.")

    # root_relations: weighted-PMI scoring for "semantically close" roots.
    #
    # Plain PMI rewards rare-rare pairs that happen to co-occur a few times,
    # which surfaces noise (e.g. unrelated rare roots paired with very common
    # ones like ك و ن). To bias toward statistically reliable relationships
    # we use:
    #     score = PMI(A,B) × √(c_AB)
    # which keeps the relative-frequency signal of PMI but penalises pairs
    # backed by only a handful of co-occurrences. Combined with min_count=5
    # this gives much cleaner "topically related" lists.
    ayah_root_count: Counter = Counter()
    for roots in ayah_roots.values():
        for r in roots:
            ayah_root_count[r] += 1
    n_ayahs = len(ayah_roots)

    pair_count: Counter = Counter()
    for roots in ayah_roots.values():
        rl = sorted(roots)
        for i in range(len(rl)):
            for j in range(i + 1, len(rl)):
                pair_count[(rl[i], rl[j])] += 1

    MIN_COUNT = 5
    by_root: defaultdict[str, list[tuple[str, float, int]]] = defaultdict(list)
    for (a, b), c_ab in pair_count.items():
        if c_ab < MIN_COUNT:
            continue
        c_a = ayah_root_count[a]
        c_b = ayah_root_count[b]
        pmi = math.log((c_ab * n_ayahs) / (c_a * c_b))
        if pmi <= 0:
            continue
        weighted = pmi * math.sqrt(c_ab)
        by_root[a].append((b, weighted, c_ab))
        by_root[b].append((a, weighted, c_ab))

    relation_rows = []
    for a, rels in by_root.items():
        rels.sort(key=lambda x: (-x[1], -x[2]))
        for b, w, _c in rels[:12]:
            relation_rows.append((root_id_by_letters[a], root_id_by_letters[b], int(w * 1000)))
    cur.executemany(
        "INSERT INTO root_relations (root_id, related_root_id, score) VALUES (?,?,?)",
        relation_rows,
    )
    print(f"      {len(relation_rows):,} related-root edges (weighted-PMI).")

    # ---- Semantic neighbors via TF-IDF cosine ----
    # Prefer the high-precision QAC per-root glosses if available (much cleaner
    # signal than ayah-level Sahih, since each root's bag is restricted to its
    # own dictionary entry).
    from collections import Counter as _C
    import json as _json
    qac_path = CACHE / "qac_glosses.json"
    root_tokens: dict[str, _C] = defaultdict(_C)
    semantic_source = None

    if qac_path.exists():
        try:
            # Same tokenizer as the non-Quranic synonym builder: drops
            # grammatical labels (noun/participle/form iii…), stems English
            # inflections, and marks negated words ("lose courage").
            from build_external_synonyms import tokens_from_words
            qac_data = _json.loads(qac_path.read_text(encoding="utf-8"))
            for root_letters, toks in qac_data.items():
                if toks:
                    root_tokens[root_letters].update(
                        tokens_from_words([t.lower() for t in toks]))
            if root_tokens:
                semantic_source = "QAC glosses"
        except Exception as e:
            print(f"      QAC glosses unreadable: {e}")

    if not root_tokens and sahih_path is not None:
        # Fallback: ayah-level Sahih.
        print("      computing semantic neighbors (Sahih TF-IDF fallback)…")
        sahih = parse_tanzil(sahih_path)
        for (s, a), roots in ayah_roots.items():
            text = sahih.get((s, a))
            if not text:
                continue
            toks = tokenize_english(text)
            if not toks:
                continue
            tc = _C(toks)
            for r in roots:
                root_tokens[r].update(tc)
        if root_tokens:
            semantic_source = "Sahih TF-IDF"

    if root_tokens:
        print(f"      computing semantic neighbors ({semantic_source})…")

        # IDF over roots (a "document" = a root's bag).
        df: _C = _C()
        for r, bag in root_tokens.items():
            for tok in bag.keys():
                df[tok] += 1
        n_docs = len(root_tokens)
        idf: dict[str, float] = {}
        for tok, d in df.items():
            # Skip ultra-common (in >70% of roots) and ultra-rare (only one).
            if d <= 1 or d > n_docs * 0.7:
                continue
            idf[tok] = math.log(n_docs / d)

        # Build TF-IDF L2-normalized vectors as dicts.
        vecs: dict[str, dict[str, float]] = {}
        for r, bag in root_tokens.items():
            total = sum(bag.values())
            if total == 0:
                continue
            v: dict[str, float] = {}
            for tok, c in bag.items():
                w = idf.get(tok)
                if w is None:
                    continue
                v[tok] = (c / total) * w
            norm = math.sqrt(sum(x * x for x in v.values()))
            if norm > 0:
                for tok in v:
                    v[tok] /= norm
                vecs[r] = v

        # Inverted index for sparse cosine.
        token_to_roots: defaultdict[str, list[tuple[str, float]]] = defaultdict(list)
        for r, v in vecs.items():
            for tok, w in v.items():
                token_to_roots[tok].append((r, w))

        sim: defaultdict[tuple[str, str], float] = defaultdict(float)
        for tok, posts in token_to_roots.items():
            # Skip tokens that hit too many roots — they contribute O(n²) work.
            if len(posts) > 500:
                continue
            for i in range(len(posts)):
                ra, wa = posts[i]
                for j in range(i + 1, len(posts)):
                    rb, wb = posts[j]
                    if ra < rb:
                        sim[(ra, rb)] += wa * wb
                    else:
                        sim[(rb, ra)] += wa * wb

        sem_by_root: defaultdict[str, list[tuple[str, float]]] = defaultdict(list)
        for (ra, rb), c in sim.items():
            if c < 0.05:
                continue
            sem_by_root[ra].append((rb, c))
            sem_by_root[rb].append((ra, c))

        sem_rows = []
        for r, lst in sem_by_root.items():
            lst.sort(key=lambda x: -x[1])
            for r2, c in lst[:12]:
                if r in root_id_by_letters and r2 in root_id_by_letters:
                    sem_rows.append((root_id_by_letters[r], root_id_by_letters[r2], int(c * 10000)))
        cur.executemany(
            "INSERT INTO root_semantic (root_id, related_root_id, score) VALUES (?,?,?)",
            sem_rows,
        )
        print(f"      {len(sem_rows):,} semantic edges ({semantic_source} cosine).")
    else:
        print("      skipped semantic neighbors (no glosses/translation).")

    # ---- Morphological neighbors: ≥2 shared letters, with position bonus ----
    print("      computing morphological neighbors…")
    roots_list = list(root_occurrences.keys())
    # Bucket by 2-letter pairs so we don't do n² letter comparisons blindly.
    pair_index: defaultdict[str, list[str]] = defaultdict(list)
    for r in roots_list:
        letters = list(r)
        for i in range(len(letters)):
            for j in range(i + 1, len(letters)):
                pair_index[letters[i] + letters[j]].append(r)
                pair_index[letters[j] + letters[i]].append(r)

    morph_scores: defaultdict[tuple[str, str], int] = defaultdict(int)
    for r in roots_list:
        letters = list(r)
        seen: set[str] = set()
        for i in range(len(letters)):
            for j in range(i + 1, len(letters)):
                for other in pair_index.get(letters[i] + letters[j], ()):
                    if other == r or other in seen:
                        continue
                    seen.add(other)
                    # Count shared chars (multiset) and same-position matches.
                    a = list(r)
                    b = list(other)
                    ca = Counter(a)
                    cb = Counter(b)
                    shared = sum((ca & cb).values())
                    if shared < 2:
                        continue
                    pos = 0
                    for k in range(min(len(a), len(b))):
                        if a[k] == b[k]:
                            pos += 1
                    score = shared * 10 + pos * 5
                    key = (r, other) if r < other else (other, r)
                    if score > morph_scores[key]:
                        morph_scores[key] = score

    morph_by_root: defaultdict[str, list[tuple[str, int]]] = defaultdict(list)
    for (a, b), s in morph_scores.items():
        morph_by_root[a].append((b, s))
        morph_by_root[b].append((a, s))

    morph_rows = []
    for r, lst in morph_by_root.items():
        # Sort by score desc, then by occurrence count desc so common roots
        # come first among equally-scored ties.
        lst.sort(key=lambda x: (-x[1], -root_occurrences.get(x[0], 0)))
        for r2, sc in lst[:12]:
            if r in root_id_by_letters and r2 in root_id_by_letters:
                morph_rows.append((root_id_by_letters[r], root_id_by_letters[r2], sc))
    cur.executemany(
        "INSERT INTO root_morpho (root_id, related_root_id, score) VALUES (?,?,?)",
        morph_rows,
    )
    print(f"      {len(morph_rows):,} morphological edges.")

    # ---- Per-letter statistics ----
    print("      building letter statistics…")
    build_letters(cur, uthmani, simple, root_occurrences)

    # ---- Non-Quranic roots → Quranic synonyms ----
    build_external(cur, root_id_by_letters)

    # ---- التقابل (opposites) ----
    build_opposites(cur, root_id_by_letters)

    # ---- Short meanings of Quranic roots (التقاليب, خ-21) ----
    build_root_glosses(cur, root_id_by_letters)

    # ---- Sahihayn hadith behind the quality gate (خ-22 / خ-23) ----
    from build_hadith import build_hadith
    build_hadith(cur, {rid: l for l, rid in root_id_by_letters.items()})

    # ---- التصريف والميزان الصرفي (خ-40 م1) ----
    from build_wazn import build_word_morph, export_review
    morph_stats = build_word_morph(cur, word_segments)
    print(f"      word morphology: {dict(morph_stats)}")
    export_review(cur, ROOT / "review" / "wazn_review_v1.tsv")
    from build_hadith_morph import build_hadith_morph
    print(f"      hadith morphology: {dict(build_hadith_morph(cur))}")

    conn.commit()
    print(f"[5/5] Wrote {DB_PATH} ({DB_PATH.stat().st_size/1024/1024:.1f} MB)")
    conn.close()
    return 0


_DIACRITICS = re.compile("[ً-ْٰٱۖ-ۭ]")


def strip_diacritics(s: str) -> str:
    return _DIACRITICS.sub("", s)


# Search normalization — MUST stay identical to the Kotlin `normalizeSearch`
# in SearchNormalize.kt. Removes combining marks/tatweel and folds alif/hamza/
# ya/ta-marbuta variants to a common base so a typed word matches the corpus.
_SEARCH_MAP = {
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا",
    "ى": "ي", "ؤ": "و", "ئ": "ي", "ة": "ه",
}


def _is_search_mark(cp: int) -> bool:
    return (
        0x0610 <= cp <= 0x061A or
        0x064B <= cp <= 0x065F or
        cp == 0x0670 or
        0x06D6 <= cp <= 0x06ED or
        cp == 0x0640
    )


def normalize_search(w: str) -> str:
    out = []
    for ch in w:
        if _is_search_mark(ord(ch)):
            continue
        out.append(_SEARCH_MAP.get(ch, ch))
    return "".join(out).strip()


def search_variants(w: str) -> set:
    """Normalized variants of a word so that imlaa'i typing matches Uthmani
    rasm. The Kotlin query side only ever produces the base variant (users don't
    type dagger-alif), so indexing all three here bridges the gap:
      - base:        dagger alif (ٰ) dropped        (رحمٰن → رحمن)
      - dagger→alif: dagger alif becomes ا           (مٰلك → مالك)
      - waw/ya long-a: وٰ/يٰ become ا                 (صلوٰة → صلاة, حيوٰة → حياة)
    """
    variants = {normalize_search(w)}
    variants.add(normalize_search(w.replace("ٰ", "ا")))
    variants.add(normalize_search(w.replace("وٰ", "ا").replace("يٰ", "ا")))
    variants.discard("")
    return variants


# ---------------------------------------------------------------------------
# Letter reference data
# ---------------------------------------------------------------------------

# Hija'i order (the 28 base letters).
HIJA_ORDER = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"

# Abjad order + numeric values (أبجد هوز حطي كلمن سعفص قرشت ثخذ ضظغ).
ABJAD = [
    ("ا", 1), ("ب", 2), ("ج", 3), ("د", 4), ("ه", 5), ("و", 6), ("ز", 7),
    ("ح", 8), ("ط", 9), ("ي", 10), ("ك", 20), ("ل", 30), ("م", 40),
    ("ن", 50), ("س", 60), ("ع", 70), ("ف", 80), ("ص", 90), ("ق", 100),
    ("ر", 200), ("ش", 300), ("ت", 400), ("ث", 500), ("خ", 600), ("ذ", 700),
    ("ض", 800), ("ظ", 900), ("غ", 1000),
]

# The 14 "luminous"/disjoined letters that open surahs (نصف حروف الهجاء).
MUQATTAA = set("المصركهيعطسحقن")

# Makhraj group key per letter (keys documented in letters_lore.json).
MAKHRAJ_GROUP = {
    "ا": "jawf", "و": "jawf", "ي": "jawf",           # الجوف (with madd)
    "ء": "halq_aqsa", "ه": "halq_aqsa",              # أقصى الحلق
    "ع": "halq_wasat", "ح": "halq_wasat",            # وسط الحلق
    "غ": "halq_adna", "خ": "halq_adna",              # أدنى الحلق
    "ق": "lisan_aqsa_q",                             # أقصى اللسان (فوق)
    "ك": "lisan_aqsa_k",                             # أقصى اللسان (تحت)
    "ج": "lisan_wasat", "ش": "lisan_wasat",          # وسط اللسان
    "ض": "lisan_hafa",                               # حافة اللسان
    "ل": "lisan_adna_hafa",                          # أدنى حافة اللسان
    "ن": "lisan_taraf_n",                            # طرف اللسان (مع الخيشوم)
    "ر": "lisan_taraf_r",                            # طرف اللسان (منحرف)
    "ط": "lisan_nita", "د": "lisan_nita", "ت": "lisan_nita",   # النطع
    "ص": "lisan_asala", "س": "lisan_asala", "ز": "lisan_asala",# الأسلة (صفير)
    "ظ": "lisan_litha", "ذ": "lisan_litha", "ث": "lisan_litha",# اللثة (بين الأسنان)
    "ف": "shafa_fa",                                 # الشفة مع الثنايا
    "ب": "shafatan", "م": "shafatan",                # الشفتان
}

# Map of Arabic diacritic/hamza variants → base letter for frequency counting.
_LETTER_NORMALIZE = {
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه",
    "ؤ": "و", "ئ": "ي",
}
_LETTER_DROP = set("ءًٌٍَُِّْٰـ") | set("ۖۗۘۙۚۛۜ۝۞ۣ۟۠ۡۢۤۦۧۨ۩۪ۭ")


def _count_letters(texts) -> Counter:
    """Count base-letter frequencies across a collection of ayah strings,
    normalizing hamza/alif variants to the 28-letter base alphabet."""
    freq: Counter = Counter()
    base = set(HIJA_ORDER)
    for t in texts:
        for ch in t:
            if ch in _LETTER_DROP:
                continue
            ch = _LETTER_NORMALIZE.get(ch, ch)
            if ch in base:
                freq[ch] += 1
    return freq


def build_external(cur, root_id_by_letters: dict):
    """Load cache/external_synonyms.json (from build_external_synonyms.py)."""
    import json as _json
    path = CACHE / "external_synonyms.json"
    if not path.exists():
        print("      skipped external roots (run build_external_synonyms.py).")
        return
    data = _json.loads(path.read_text(encoding="utf-8"))
    root_rows, syn_rows = [], []
    for letters, info in data.items():
        if letters in root_id_by_letters:
            continue  # it is Quranic after all
        syns = [(root_id_by_letters[q], int(s * 10000))
                for q, s in info.get("syn", []) if q in root_id_by_letters]
        # Keep roots with a meaning even without synonyms (needed by التقاليب).
        if not syns and not info.get("gloss"):
            continue
        root_rows.append((letters, info.get("gloss", "")))
        syn_rows += [(letters, rid, sc) for rid, sc in syns]
    cur.executemany("INSERT INTO external_roots (letters, gloss) VALUES (?,?)", root_rows)
    cur.executemany(
        "INSERT INTO external_synonyms (ext_letters, root_id, score) VALUES (?,?,?)", syn_rows)
    print(f"      {len(root_rows):,} non-Quranic roots, {len(syn_rows):,} Quranic synonym links.")


_DXE = re.compile(r'<h4 class="dxe">([^<]+)</h4>')


def build_root_glosses(cur, root_id_by_letters: dict):
    """First meaningful QAC dictionary heading per root ("Verb (form I) -to
    know" → "to know"); Wiktionary gloss as fallback."""
    import json as _json
    from fetch_qac_glosses import arabic_to_buckwalter
    html_dir = CACHE / "qac_html"
    wk_path = CACHE / "wiktionary_quranic_glosses.json"
    wk = _json.loads(wk_path.read_text(encoding="utf-8")) if wk_path.exists() else {}

    rows = []
    for letters, rid in root_id_by_letters.items():
        # Code-point-named files are authoritative (case-safe refetch); the
        # older Buckwalter-named ones are valid for roots without collisions.
        cp = html_dir / ("_".join(f"{ord(ch):04x}" for ch in letters) + ".html")
        bw = html_dir / (arabic_to_buckwalter(letters) + ".html")
        gloss = None
        for f in (cp, bw):
            if not f.exists():
                continue
            for head in _DXE.findall(f.read_text(encoding="utf-8", errors="ignore")):
                if " -" in head:
                    g = head.split(" -", 1)[1].strip()
                    if g:
                        gloss = g
                        break
            if gloss:
                break
        if gloss:
            rows.append((rid, gloss[:80], "qac"))
        elif wk.get(letters):
            rows.append((rid, wk[letters][:80], "wiktionary"))
    cur.executemany("INSERT INTO root_glosses (root_id, gloss, source) VALUES (?,?,?)", rows)
    print(f"      {len(rows):,} root meanings (of {len(root_id_by_letters):,}).")


def build_opposites(cur, root_id_by_letters: dict):
    """Load cache/opposites.json (from build_opposites.py), both directions."""
    import json as _json
    path = CACHE / "opposites.json"
    if not path.exists():
        print("      skipped opposites (run build_opposites.py).")
        return
    rows = []
    for p in _json.loads(path.read_text(encoding="utf-8")):
        a, b = root_id_by_letters.get(p["a"]), root_id_by_letters.get(p["b"])
        if a is None or b is None:
            continue
        rows.append((a, b, p["source"], p["co"], p["label"]))
        rows.append((b, a, p["source"], p["co"], p["label"]))
    cur.executemany(
        "INSERT OR IGNORE INTO root_opposites "
        "(root_id, opposite_root_id, source, cooccur, label) VALUES (?,?,?,?,?)", rows)
    print(f"      {len(rows) // 2:,} opposite pairs (التقابل).")


def build_letters(cur, uthmani: dict, simple: dict, root_occurrences: Counter):
    freq_simple = _count_letters(simple.values())
    freq_uthmani = _count_letters(uthmani.values())

    # Root-based stats.
    root_count: Counter = Counter()
    weighted_occ: Counter = Counter()
    for letters, occ in root_occurrences.items():
        for ch in set(letters):
            root_count[ch] += 1
            weighted_occ[ch] += occ

    hija_index = {ch: i + 1 for i, ch in enumerate(HIJA_ORDER)}
    abjad_index = {ch: i + 1 for i, (ch, _v) in enumerate(ABJAD)}
    abjad_value = {ch: v for ch, v in ABJAD}

    rows = []
    for ch in HIJA_ORDER:
        rows.append((
            ch,
            hija_index[ch],
            abjad_index[ch],
            abjad_value[ch],
            freq_simple.get(ch, 0),
            freq_uthmani.get(ch, 0),
            root_count.get(ch, 0),
            weighted_occ.get(ch, 0),
            1 if ch in MUQATTAA else 0,
            MAKHRAJ_GROUP.get(ch, ""),
        ))
    cur.executemany(
        "INSERT INTO letters (letter, hija_order, abjad_order, abjad_value, "
        "freq_simple, freq_uthmani, root_count, weighted_occ, is_muqattaa, "
        "makhraj_group) VALUES (?,?,?,?,?,?,?,?,?,?)",
        rows,
    )
    print(f"      {len(rows)} letters recorded.")


if __name__ == "__main__":
    sys.exit(main())
