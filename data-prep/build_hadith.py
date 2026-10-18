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

خ-27ب — outside the Sahihayn, narrowly: for Quranic roots with fewer than
THIN_UNITS hadith in the Sahihayn, ship the representative text of Dorar
units that have no Sahihayn narration and whose best grading is tier A
(صحيح / إسناده صحيح / ثابت). Only those thin roots are linked in these
texts, so common roots keep their Sahihayn-only lists. track = 'dorar'.

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
    "QR_HADITH_DB", Path.home() / "Documents" / "Claude" / "Dorar" / "data" / "hadith.db"))

_FOLD = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"}
_VOWELS = set(chr(c) for c in range(0x064B, 0x0651))   # tanwin + short vowels
_SHADDA = "\u0651"
THIN_UNITS = 5          # خ-27ب: a root with fewer Sahihayn hadith than this gets outside texts
OUTSIDE_HID_BASE = 10_000_000


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
    hid INTEGER PRIMARY KEY,      -- Sahihayn hid; outside texts: 10_000_000 + Dorar unit_id
    track TEXT NOT NULL,          -- sahihayn | dorar (خ-27ب)
    book TEXT NOT NULL,           -- صحيح البخاري | صحيح مسلم | (outside) the grading's source book
    hadith_no TEXT,
    chapter TEXT,                 -- الكتاب
    section TEXT,                 -- الباب
    isnad TEXT,
    tokens TEXT NOT NULL,         -- matn words joined by single spaces (word_no = index+1)
    unit_id INTEGER NOT NULL,     -- خ-26: narrations of one hadith share a unit (= hid when unknown)
    is_rep INTEGER NOT NULL,      -- 1 for the unit's representative narration
    other_narrations INTEGER NOT NULL, -- narrations of this unit outside the Sahihayn (Dorar)
    url TEXT                      -- خ-27ب: the text's page on dorar.net (outside texts only)
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

    def _clean(v):
        """Empty CSV cells reach the source DB as the text 'nan'."""
        v = (v or "").strip("‏ ")
        return None if not v or v.lower() == "nan" else v

    rows = []
    for hid, book, no, ch, sec, isnad in src.execute(
            "SELECT hid, book, hadith_no, chapter_ar, section_ar, isnad FROM hadith ORDER BY hid"):
        unit_id, is_rep, n_other = unit_of.get(hid, (hid, 1, 0))
        rows.append([hid, "sahihayn", book, (no or "").removesuffix(".0"), _clean(ch), _clean(sec), _clean(isnad), None,
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

    for hid, toks in tokens.items():
        if hid in by_hid:
            by_hid[hid][7] = " ".join(toks)
    rows = [tuple(r) for r in rows if r[7]]
    cur.executemany(
        "INSERT INTO hadith (hid, track, book, hadith_no, chapter, section, isnad, tokens, "
        "unit_id, is_rep, other_narrations) VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    # A unit whose representative narration has no words must still have one.
    # Pick the smallest hid in it (Bukhari first) as the shown narration.
    units = defaultdict(list)
    for r in rows:
        units[r[8]].append(r)
    for rs in units.values():
        if not any(r[9] for r in rs):
            cur.execute("UPDATE hadith SET is_rep = 1 WHERE hid = ?", (rs[0][0],))  # hid-ordered → Bukhari first

    # خ-27ب: outside the Sahihayn for thin roots.
    n_outside = 0
    if {"dorar_sahih_text", "dorar_words", "dorar_words_final"} <= have and unit_of:
        units_by_root: defaultdict[int, set] = defaultdict(set)
        for rid, hid, _ in postings:
            units_by_root[rid].add(unit_of.get(hid, (hid,))[0])
        thin = {rid for rid in root_letters_by_id if len(units_by_root.get(rid, ())) < THIN_UNITS}
        n_outside = build_outside(cur, src, thin, root_letters_by_id, qidx, rules, stats)
    src.close()
    cur.executemany(
        "INSERT OR IGNORE INTO hadith_root_words (root_id, hid, word_no) VALUES (?,?,?)", postings)
    n_units = len({r[8] for r in rows})
    print(f"      hadith: {len(rows):,} ahadith in {n_units:,} units "
          f"({'Dorar units' if unit_of else 'no unit table'}; "
          f"final roots {'yes' if final_root else 'no'}), {len(postings):,} root links "
          f"(confirmed by Quran {stats['quran_confirmed']:,}, corrected {stats['quran_corrected']:,}, "
          f"curated {stats['curated_override']:,}, "
          f"proper nouns unlinked {stats['proper_noun_unlinked']:,}).")
    if n_outside:
        print(f"      outside the Sahihayn (خ-27ب): {n_outside:,} tier-A texts for "
              f"{stats['outside_roots']:,} thin roots, {stats['outside_links']:,} links.")


def _gate(text, rid, is_proper, root_letters_by_id, qidx, rules, stats):
    """The same quality gate as the Sahihayn words; returns the root id or None."""
    from build_db import normalize_search
    if rid is None:
        return None
    if is_proper and root_letters_by_id.get(rid) != "اله":
        stats["proper_noun_unlinked"] += 1
        return None
    c = qidx.get(light_key(text))
    if c and len(c) == 1:
        (qrid, n), = c.items()
        if qrid != rid and n >= 2:
            rid = qrid
            stats["quran_corrected"] += 1
        elif qrid == rid:
            stats["quran_confirmed"] += 1
    norm = normalize_search(text)
    for frm, field, pat, to in rules:
        if rid == frm and pat.search(text if field == "raw" else norm):
            stats["curated_override"] += 1
            return to
    return rid


def build_outside(cur, src, thin, root_letters_by_id, qidx, rules, stats) -> int:
    import json
    cands = src.execute("""
        SELECT u.unit_id, t.text_key, u.n_dorar_w, t.best_label, t.rulings_json, t.narrators_json
        FROM hadith_unit u JOIN wording w ON w.wording_id = u.rep_wording_id
        JOIN dorar_sahih_text t ON 'D:' || t.text_key = w.key
        WHERE u.n_sahihayn_w = 0 AND t.best_tier = 'A'""").fetchall()
    rows, postings, roots_hit = [], [], set()
    for unit_id, tk, n_w, label, rulings, narrators in cands:
        words = src.execute(
            "SELECT w.word_no, w.text, f.root_id, f.is_proper FROM dorar_words w "
            "LEFT JOIN dorar_words_final f ON f.text_key = w.text_key AND f.word_no = w.word_no "
            "WHERE w.text_key = ? ORDER BY w.word_no", (tk,)).fetchall()
        if not words:
            continue
        links = []
        for wn, text, rid, prop in words:
            rid = _gate(text, rid, prop or 0, root_letters_by_id, qidx, rules, stats)
            if rid in thin:
                links.append((rid, wn))
        if not links:
            continue
        hid = OUTSIDE_HID_BASE + unit_id
        r0 = (json.loads(rulings or "[]") or [{}])[0]
        # Dorar's terms ask that every text link back to its page; skip texts without one.
        url = (src.execute("SELECT url FROM dorar_tag WHERE hit_key = ?", (r0.get("hit_key"),)).fetchone()
               if r0.get("hit_key") else None)
        if not url:
            stats["outside_no_url"] += 1
            continue
        narr = (json.loads(narrators or "[]") or [{}])[0].get("narrator") or ""
        section = (label or "").replace("_", " ")
        if r0.get("scholar"):
            section = f"{section} — {r0['scholar']}".strip(" —")
        rows.append((hid, "dorar", r0.get("source") or "خارج الصحيحين", r0.get("number") or "",
                     None, section or None, narr.strip("[] ") or None,
                     " ".join(t for _, t, _, _ in words), unit_id, 1, max(n_w - 1, 0), url[0]))
        for rid, wn in links:
            postings.append((rid, hid, wn))
            roots_hit.add(rid)
    cur.executemany(
        "INSERT INTO hadith (hid, track, book, hadith_no, chapter, section, isnad, tokens, "
        "unit_id, is_rep, other_narrations, url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    cur.executemany(
        "INSERT OR IGNORE INTO hadith_root_words (root_id, hid, word_no) VALUES (?,?,?)", postings)
    stats["outside_roots"] = len(roots_hit)
    stats["outside_links"] = len(postings)
    return len(rows)

