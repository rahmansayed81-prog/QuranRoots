"""
خ-64: invariants for the app database. Runs at the end of build_db.py and
fails the build when any check breaks, so a fixed bug cannot come back
silently. Run alone with:  python check_db.py [path-to-db]

Each check names the bug or decision it guards. Add one whenever a data
bug is fixed.
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).parent
DEFAULT_DB = HERE.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"
KOTLIN_SRC = HERE.parent / "app" / "src" / "main" / "java" / "com" / "quranroots" / "app" / "data" / "db" / "QuranDataSource.kt"
VERSION_LOCK = HERE / "db_version_lock.json"

_ARABIC_LETTERS = re.compile(r"^[ء-ي]+$")
_DIACRITICS = re.compile("[ً-ْٰـ]")


def _plain(s: str) -> str:
    return _DIACRITICS.sub("", s or "").replace("ٱ", "ا")


class Checks:
    def __init__(self, con: sqlite3.Connection):
        self.con = con
        self.failures: list[str] = []
        self.passed = 0

    def q(self, sql: str, *args):
        return self.con.execute(sql, args).fetchall()

    def one(self, sql: str, *args):
        return self.con.execute(sql, args).fetchone()[0]

    def expect(self, name: str, ok: bool, detail: str = "") -> None:
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{name}: {detail}")

    def has(self, table: str) -> bool:
        return bool(self.q("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", table))


def run(db: Path) -> list[str]:
    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    c = Checks(con)

    # --- Quran text --------------------------------------------------------
    c.expect("surahs", c.one("SELECT COUNT(*) FROM surahs") == 114)
    c.expect("ayahs", c.one("SELECT COUNT(*) FROM ayahs") == 6236)
    # ق-1: the basmala belongs to ayah 1 of al-Fatiha only (and al-Tawba has none).
    bad = c.q("SELECT surah_no FROM ayahs WHERE ayah_no = 1 AND surah_no NOT IN (1, 9) "
              "AND text_simple LIKE 'بسم الله الرحمن الرحيم%'")
    c.expect("basmala stripped (ق-1)", not bad, f"{len(bad)} surahs still start with it, e.g. {bad[:3]}")

    # --- Roots -------------------------------------------------------------
    n_roots = c.one("SELECT COUNT(*) FROM roots")
    c.expect("roots count", 1600 <= n_roots <= 1700, str(n_roots))
    odd = [l for (l,) in c.q("SELECT letters FROM roots") if not _ARABIC_LETTERS.match(l or "")]
    c.expect("root letters are plain Arabic (stray symbols audit)", not odd, f"{odd[:5]}")
    c.expect("roots.occurrences = words per root",
             c.one("SELECT COUNT(*) FROM roots r WHERE occurrences <> "
                   "(SELECT COUNT(*) FROM words w WHERE w.root_id = r.root_id)") == 0)
    c.expect("roots.forms_count = distinct lemmas per root",
             c.one("SELECT COUNT(*) FROM roots r WHERE forms_count <> "
                   "(SELECT COUNT(DISTINCT lemma) FROM words w WHERE w.root_id = r.root_id)") == 0)
    bad_lemmas = c.one("SELECT COUNT(*) FROM words WHERE lemma GLOB '*[0-9()]*'")
    c.expect("lemmas without digits/brackets (clean_lemma)", bad_lemmas == 0, f"{bad_lemmas} lemmas")

    # --- Word search (v16: counts by mushaf position, not surface+lemma) ---
    c.expect("word_forms: الله = 2699 positions",
             c.one("SELECT count FROM word_forms WHERE form_plain = 'الله' AND root_id = "
                   "(SELECT root_id FROM roots WHERE letters = 'اله')") == 2699)
    c.expect("word_forms: انكدرت = 1 position",
             c.one("SELECT COALESCE(MAX(count), 0) FROM word_forms WHERE form_plain = 'انكدرت'") == 1)
    over = c.one("SELECT COUNT(*) FROM word_forms f WHERE f.count > "
                 "(SELECT occurrences FROM roots r WHERE r.root_id = f.root_id)")
    c.expect("word_forms.count never exceeds the root's occurrences", over == 0, f"{over} forms")

    # --- Letters -----------------------------------------------------------
    c.expect("letters = 28", c.one("SELECT COUNT(*) FROM letters") == 28)

    # --- Hadith ------------------------------------------------------------
    if c.has("hadith"):
        c.expect("Sahihayn narrations present", c.one("SELECT COUNT(*) FROM hadith WHERE track='sahihayn'") > 13000)
        # v17: empty CSV cells used to show as «باب: nan».
        nan = c.one("SELECT COUNT(*) FROM hadith WHERE lower(trim(chapter))='nan' "
                    "OR lower(trim(section))='nan' OR lower(trim(isnad))='nan'")
        c.expect("no literal 'nan' in hadith fields (v17)", nan == 0, f"{nan} rows")
        # خ-26: every unit shows one narration first.
        no_rep = c.one("SELECT COUNT(*) FROM (SELECT unit_id FROM hadith GROUP BY unit_id HAVING SUM(is_rep) = 0)")
        c.expect("every hadith unit has a representative (خ-26)", no_rep == 0, f"{no_rep} units")
        # خ-27ب: Dorar's terms — every outside text links back to its page.
        no_url = c.one("SELECT COUNT(*) FROM hadith WHERE track <> 'sahihayn' AND (url IS NULL OR url = '')")
        c.expect("every Dorar text has its dorar.net link (خ-27ب)", no_url == 0, f"{no_url} rows")
        orphans = c.one("SELECT COUNT(*) FROM hadith_root_words w LEFT JOIN hadith h USING (hid) WHERE h.hid IS NULL")
        c.expect("hadith_root_words point at existing hadith", orphans == 0, f"{orphans} links")
        # Every linked word number must exist in the hadith text.
        out_of_range = 0
        lens = {hid: len((t or "").split(" ")) for hid, t in c.q("SELECT hid, tokens FROM hadith")}
        for hid, wn in c.q("SELECT hid, word_no FROM hadith_root_words"):
            if not (1 <= wn <= lens.get(hid, 0)):
                out_of_range += 1
        c.expect("hadith word numbers inside the text", out_of_range == 0, f"{out_of_range} links")
        # Quality gate anchors.
        nwy = c.one("SELECT root_id FROM roots WHERE letters = 'نوي'")
        niyyat = sum(1 for (t,) in c.q("SELECT h.tokens FROM hadith_root_words w JOIN hadith h USING (hid) "
                                        "WHERE w.root_id = ?", nwy) if "النيات" in _plain(t))
        c.expect("«النيات» linked to نوي (curated override)", niyyat >= 1, f"{niyyat}")
        # Proper nouns: «عمر» as a name must not count for the root عمر in the Sahihayn opening hadith.
        umar_names = c.one(
            "SELECT COUNT(*) FROM hadith_root_words w JOIN roots r USING (root_id) JOIN hadith h USING (hid) "
            "WHERE r.letters = 'عمر' AND h.hid = (SELECT MIN(hid) FROM hadith WHERE track='sahihayn')")
        c.expect("proper noun عمر not linked in Bukhari 1", umar_names == 0, f"{umar_names} links")

    con.close()
    return c.failures, c.passed


def logical_hash(db: Path) -> str:
    """Hash of every table's rows in a stable order: changes only when the data does."""
    con = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    h = hashlib.sha256()
    for (t,) in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
        h.update(t.encode())
        for row in con.execute(f'SELECT * FROM "{t}" ORDER BY 1, 2'):
            h.update(repr(row).encode())
    con.close()
    return h.hexdigest()


def check_version(db: Path) -> str | None:
    """The app caches the DB under DB_VERSION: new data with the same version never reaches
    installed phones. Fail if the data changed but DB_VERSION did not."""
    m = re.search(r"DB_VERSION\s*=\s*(\d+)", KOTLIN_SRC.read_text(encoding="utf-8"))
    if not m:
        return "DB_VERSION not found in QuranDataSource.kt"
    version = m.group(1)
    digest = logical_hash(db)
    lock = json.loads(VERSION_LOCK.read_text(encoding="utf-8")) if VERSION_LOCK.exists() else {}
    known = lock.get(version)
    if known is None:
        lock[version] = digest
        VERSION_LOCK.write_text(json.dumps(lock, indent=1, sort_keys=True), encoding="utf-8")
        return None
    if known != digest:
        return (f"the database changed but DB_VERSION is still {version}: bump DB_VERSION in "
                f"QuranDataSource.kt, or installed apps will keep the old copy")
    return None


def main(argv: list[str]) -> int:
    db = Path(argv[1]) if len(argv) > 1 else DEFAULT_DB
    failures, passed = run(db)
    v = check_version(db)
    if v:
        failures.append(f"DB_VERSION: {v}")
    if failures:
        print(f"      DB checks: {passed} passed, {len(failures)} FAILED")
        for f in failures:
            print(f"        ✗ {f}")
        return 1
    print(f"      DB checks: all {passed} passed; DB_VERSION matches the data.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
