"""
Bring the Sahihayn hadith corpus (from the Dorar project, read-only) into the
app DB, behind a quality gate. Called from build_db.py.

Source:  Dorar/data/hadith.db  (tables hadith, hadith_words, hadith_words_final,
         wording, wording_occ, hadith_unit) — never written to.
         Override the path with env QR_HADITH_DB.

خ-26 — hadith units: Dorar groups narrations of one hadith into a unit
(hadith ← wording ← occurrence). Each row carries its unit_id, whether it is
the unit's representative narration, and how many narrations of the same
unit exist outside the Sahihayn (count only; their texts are not shipped).

Quality gate (per word that the Dorar pipeline gave a root):
  1. Proper nouns (pos = noun_prop) are NOT linked to roots — «عمر/بكر/عبد
     الرحمن» are names, not the roots عمر/بكر/رحم. Exception: «الله» → اله,
     which the Quranic Arabic Corpus itself links.
  2. Quran-verified correction: if the word's diacritized form (case ending
     ignored, shadda kept) occurs in the Quran with exactly one analysis backed
     by ≥2 occurrences, the Quranic (QAC) analysis wins — e.g. بعض: عضض→بعض,
     أدري: درر→دري, الإيمان: يمن→امن. Diacritics keep عَمِّي (uncle) apart
     from عُمْي (blind).
  Words the analyzer left without a root are never given one.
"""

from __future__ import annotations

import os
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

HADITH_DB = Path(os.environ.get(
    "QR_HADITH_DB", r"C:\Users\rahma\Documents\Claude\Dorar\data\hadith.db"))

_FOLD = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"}
_VOWELS = set(chr(c) for c in range(0x064B, 0x0651))   # tanwin + short vowels
_SHADDA = "\u0651"


def light_key(w: str) -> str:
    """Letters + harakat; dagger alif → ا; drop sukun, Quranic signs and
    tatweel; drop the final vowel(s) — the case ending varies by context —
    but keep a final shadda (أَمَرَ ≠ أَمَرُّ)."""
    out = []
    for ch in w:
        cp = ord(ch)
        if ch == "\u0670":
            out.append("ا")
        elif ch in _VOWELS or ch == _SHADDA:
            out.append(ch)
        elif 0x0621 <= cp <= 0x064A or ch == "ٱ":
            out.append(_FOLD.get(ch, ch))
    while out and out[-1] in _VOWELS:
        out.pop()
    return "".join(out)


SCHEMA = """
CREATE TABLE IF NOT EXISTS hadith (
    hid INTEGER PRIMARY KEY,
    book TEXT NOT NULL,           -- صحيح البخاري | صحيح مسلم
    hadith_no TEXT,
    chapter TEXT,                 -- الكتاب
    section TEXT,                 -- الباب
    isnad TEXT,
    tokens TEXT NOT NULL,         -- matn words joined by single spaces (word_no = index+1)
    unit_id INTEGER NOT NULL,     -- خ-26: narrations of one hadith share a unit (= hid when unknown)
    is_rep INTEGER NOT NULL,      -- 1 for the unit's representative narration
    other_narrations INTEGER NOT NULL  -- narrations of this unit outside the Sahihayn (Dorar)
);
CREATE INDEX IF NOT EXISTS hadith_unit_idx ON hadith (unit_id);
-- Root → word positions in hadith (after the quality gate). Quranic roots only.
CREATE TABLE IF NOT EXISTS hadith_root_words (
    root_id INTEGER NOT NULL,
    hid INTEGER NOT NULL,
    word_no INTEGER NOT NULL,
    PRIMARY KEY (root_id, hid, word_no)
) WITHOUT ROWID;
"""


def build_hadith(cur, root_letters_by_id: dict[int, str]) -> None:
    if not HADITH_DB.exists():
        print(f"      skipped hadith (not found: {HADITH_DB}).")
        return
    cur.executescript(SCHEMA)

    # Quran analysis index keyed by diacritized form.
    qidx: defaultdict[str, Counter] = defaultdict(Counter)
    for text, rid in cur.execute("SELECT text, root_id FROM words"):
        qidx[light_key(text)][rid] += 1

    # Curated overrides for frequent misanalyses the Quran can't confirm.
    import json, re
    from build_db import normalize_search
    ids_by_letters = {l: i for i, l in root_letters_by_id.items()}
    rules = []
    for r in json.loads((Path(__file__).parent / "hadith_overrides.json")
                        .read_text(encoding="utf-8"))["rules"]:
        if r["from"] in ids_by_letters and r["to"] in ids_by_letters:
            rules.append((ids_by_letters[r["from"]], r.get("field", "norm"),
                          re.compile(r["pattern"]), ids_by_letters[r["to"]]))

    src = sqlite3.connect(f"file:{HADITH_DB.as_posix()}?mode=ro", uri=True)
    have = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}

    # خ-26: hid → (unit_id, is_rep, other_narrations) from Dorar's unit hierarchy.
    unit_of: dict[int, tuple[int, int, int]] = {}
    if {"wording", "wording_occ", "hadith_unit"} <= have:
        for hid, unit_id, rep_w, wid, n_dorar in src.execute("""
                SELECT CAST(o.occ_ref AS INTEGER), w.unit_id, u.rep_wording_id, w.wording_id, u.n_dorar_w
                FROM wording_occ o JOIN wording w ON w.wording_id = o.wording_id
                JOIN hadith_unit u ON u.unit_id = w.unit_id
                WHERE o.occ_kind = 'hid' AND w.unit_id IS NOT NULL"""):
            unit_of[hid] = (unit_id, int(rep_w == wid), n_dorar or 0)

    # Roots: prefer Dorar's final (voted + researched) analysis when present.
    final_root: dict[tuple[int, int], tuple[int | None, int]] = {}
    if "hadith_words_final" in have:
        for hid, wn, rid, prop in src.execute(
                "SELECT hid, word_no, root_id, is_proper FROM hadith_words_final"):
            final_root[(hid, wn)] = (rid, prop or 0)

    rows = []
    for hid, book, no, ch, sec, isnad in src.execute(
            "SELECT hid, book, hadith_no, chapter_ar, section_ar, isnad FROM hadith ORDER BY hid"):
        unit_id, is_rep, n_other = unit_of.get(hid, (hid, 1, 0))
        rows.append([hid, book, (no or "").removesuffix(".0"), ch, (sec or "").strip("‏ "), isnad, None,
                     unit_id, is_rep, n_other])
    by_hid = {r[0]: r for r in rows}

    stats: Counter = Counter()
    tokens: defaultdict[int, list] = defaultdict(list)
    postings = []
    for hid, word_no, text, root_id, pos in src.execute(
            "SELECT hid, word_no, text, root_id, pos FROM hadith_words ORDER BY hid, word_no"):
        tokens[hid].append(text)
        rid, is_proper = final_root.get((hid, word_no), (root_id, 0))
        if rid is not None:
            if (pos == "noun_prop" or is_proper) and root_letters_by_id.get(rid) != "اله":
                rid = None
                stats["proper_noun_unlinked"] += 1
            else:
                c = qidx.get(light_key(text))
                if c and len(c) == 1:
                    (qrid, n), = c.items()
                    if qrid != rid and n >= 2:
                        rid = qrid
                        stats["quran_corrected"] += 1
                    elif qrid == rid:
                        stats["quran_confirmed"] += 1
                if rid is not None:
                    norm = normalize_search(text)
                    for frm, field, pat, to in rules:
                        if rid == frm and pat.search(text if field == "raw" else norm):
                            rid = to
                            stats["curated_override"] += 1
                            break
        if rid is not None and rid in root_letters_by_id:
            postings.append((rid, hid, word_no))
    src.close()

    for hid, toks in tokens.items():
        if hid in by_hid:
            by_hid[hid][6] = " ".join(toks)
    rows = [tuple(r) for r in rows if r[6]]
    cur.executemany(
        "INSERT INTO hadith (hid, book, hadith_no, chapter, section, isnad, tokens, "
        "unit_id, is_rep, other_narrations) VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
    # A unit whose representative narration has no words must still have one.
    # Pick the smallest hid in it (Bukhari first) as the shown narration.
    units = defaultdict(list)
    for r in rows:
        units[r[7]].append(r)
    for rs in units.values():
        if not any(r[8] for r in rs):
            cur.execute("UPDATE hadith SET is_rep = 1 WHERE hid = ?", (rs[0][0],))  # hid-ordered → Bukhari first
    cur.executemany(
        "INSERT OR IGNORE INTO hadith_root_words (root_id, hid, word_no) VALUES (?,?,?)", postings)
    n_units = len({r[7] for r in rows})
    print(f"      hadith: {len(rows):,} ahadith in {n_units:,} units "
          f"({'Dorar units' if unit_of else 'no unit table'}; "
          f"final roots {'yes' if final_root else 'no'}), {len(postings):,} root links "
          f"(confirmed by Quran {stats['quran_confirmed']:,}, corrected {stats['quran_corrected']:,}, "
          f"curated {stats['curated_override']:,}, "
          f"proper nouns unlinked {stats['proper_noun_unlinked']:,}).")
