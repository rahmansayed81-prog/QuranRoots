"""
التصريف والميزان لكلمات الأحاديث (م2 من خ-40).

Only words already linked to a Quranic root (hadith_root_words) are weighed.
Two sources, in order:
  1. The Quran: the same diacritized word (light_key) with the same root in the
     Quran gives its تصريف and ميزان directly (≈ half of all links).
  2. CAMeL Tools: the analysis whose root agrees with ours and whose vowels best
     agree with the hadith's diacritics gives the word's segmentation; its stem
     (+ verb prefix and subject suffix, + noun suffixes) goes through the same
     ميزان engine as the Quran (build_wazn.weigh).

CAMeL is not a dependency of the main build. Its analyses are cached:
    <Dorar venv>/python.exe -B build_hadith_morph.py --camel
reads the built app DB (read-only), analyzes the forms the Quran cannot
cover, and writes cache/hadith_camel_v1.json. build_db.py then only reads that
cache; without it, step 2 is skipped.
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / "cache" / "hadith_camel_v1.json"
APP_DB = HERE.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"

_AR_VOWELS = set(chr(c) for c in range(0x064B, 0x0653))   # tanween, harakat, shadda, sukun
_FOLD = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي", "ء": "ا"}


def letters_vowels(w: str):
    """[(folded letter, vowels)] — for comparing a hadith token with an analysis."""
    out = []
    for ch in w:
        if ch in _AR_VOWELS:
            if out:
                out[-1][1].add(ch)
        elif ch == "ٰ":
            out.append(["ا", set()])
        elif "ء" <= ch <= "ي" or ch == "ٱ":
            out.append([_FOLD.get(ch, ch), set()])
    # an alif carrying a vowel in imlaai spelling (صَلاَة) is just ā
    for t in out:
        if t[0] == "ا":
            t[1].discard("َ")
    # CAMeL leaves the fatha before ā unwritten (واصَلَ): supply it
    for i in range(len(out) - 1):
        if out[i + 1][0] == "ا" and not out[i][1] - {"ّ"}:
            out[i][1].add("َ")
    return out


def vowel_agreement(token: str, diac: str) -> float | None:
    a, b = letters_vowels(token), letters_vowels(diac)
    if [x[0] for x in a] != [x[0] for x in b]:
        return None
    score = 0.0
    for (_, va), (_, vb) in zip(a, b):
        for v in va:
            score += 1 if v in vb else -2
        # a voweled letter without shadda in the hadith is not geminated (وَاصَلَ ≠ وَأَصَّلَ)
        if va and ("ّ" in vb) != ("ّ" in va):
            score -= 2
    return score


def root_matches(camel_root: str, ours: str) -> bool:
    parts = camel_root.split(".")
    if len(parts) != len(ours):
        return False
    for c, o in zip(parts, ours):
        c = _FOLD.get(c, c)
        if c == o or (c == "#" and o in "اوي"):
            continue
        return False
    return True


# ----------------------------------------------------------------- --camel step
def camel_step():
    from camel_tools.morphology.analyzer import Analyzer
    from camel_tools.morphology.database import MorphologyDB
    from build_hadith import light_key

    sys.stdout.reconfigure(encoding="utf-8")
    con = sqlite3.connect(f"file:{APP_DB.as_posix()}?mode=ro", uri=True)
    quran = {(light_key(t.split(" ")[0]), rid) for t, rid in con.execute(
        "SELECT w.text, w.root_id FROM words w JOIN word_morph m USING (surah_no, ayah_no, word_no) "
        "WHERE w.root_id IS NOT NULL AND m.wazn_id IS NOT NULL")}
    letters = dict(con.execute("SELECT root_id, letters FROM roots"))
    toks = {hid: t.split(" ") for hid, t in con.execute("SELECT hid, tokens FROM hadith")}
    need = set()
    for rid, hid, wn in con.execute("SELECT root_id, hid, word_no FROM hadith_root_words"):
        w = toks[hid][wn - 1]
        if (light_key(w), rid) not in quran:
            need.add((w, rid))
    print(f"forms to analyze: {len(need):,}")

    an = Analyzer(MorphologyDB.builtin_db("calima-msa-r13"))
    keep = ("diac", "bw", "pos", "asp", "vox", "mod", "num", "gen", "lex", "root")
    out, found = {}, 0
    for i, (w, rid) in enumerate(sorted(need)):
        best = None
        # CAMeL narrows its analyses by the input's diacritics in unhelpful ways
        # (حَدَّثَ yields only حَدَثَ): analyze the bare letters, then let the
        # hadith's own vowels choose.
        for a in an.analyze(re.sub("[ً-ْٰ]", "", w)):
            if not root_matches(a.get("root", ""), letters[rid]):
                continue
            sc = vowel_agreement(w, a.get("diac", ""))
            if sc is None:
                continue
            if best is None or sc > best[0]:
                best = (sc, a)
        if best:
            found += 1
            out[f"{w}|{rid}"] = {**{k: best[1].get(k) for k in keep}, "score": best[0]}
        if i % 5000 == 0:
            print(f"  {i:,} … matched {found:,}")
    CACHE.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"matched {found:,} of {len(need):,} → {CACHE.name}")


# ----------------------------------------------------------------- build step
AR2BW = None


def to_bw(ar: str) -> str:
    global AR2BW
    if AR2BW is None:
        from build_wazn import BW2AR
        AR2BW = {v: k for k, v in BW2AR.items()}
        AR2BW.update({"ا": "A", "ٱ": "{", "ٰ": "`", "ـ": ""})
    return "".join(AR2BW.get(ch, "") for ch in ar)


ROOT_BW = {"ا": "A"}

KEEP_TAGS = re.compile(r"^(IV\d\w*|PV|IV|CV|IVSUFF_SUBJ.*|PVSUFF_SUBJ.*|CVSUFF_SUBJ.*|NOUN\w*|ADJ\w*|"
                       r"NSUFF_\w*|VERB\w*|IV_PASS|PV_PASS)$")
SUBJ = re.compile(r"^(IV|PV|CV)SUFF_SUBJ")


def verb_form_from_lex(lex_bw: str) -> int:
    """باب from a CAMeL perfect lemma (Buckwalter, diacritized)."""
    s = re.sub("[aiuoFNK]", "", lex_bw.replace("{", "A"))
    if s.startswith("Ast") and len(s) >= 6:
        return 10
    if s.startswith("An") and len(s) == 5:
        return 7
    if s.startswith("A") and len(s) == 5 and s[2] in "tTd":
        return 8
    if s.startswith("t") and len(s) == 5 and s[2] == "A":
        return 6
    if s.startswith("t") and "~" in s:
        return 5
    if s.startswith((">", "A")) and len(s) == 4:
        return 4
    if len(s) == 4 and s[1] == "A":
        return 3
    if "~" in s[:3]:
        return 2
    return 1


def camel_label(a, vf: int, root_len: int) -> tuple[str, int, str, str | None]:
    """(تصريف label, verb form, QAC-style POS, aspect)."""
    from build_wazn import ASPECT, FORM_LABEL, MOOD
    pos = a.get("pos")
    if pos == "verb":
        asp = {"p": "PERF", "i": "IMPF", "c": "IMPV"}.get(a.get("asp"))
        parts = [ASPECT.get(asp, "فعل")]
        if a.get("vox") == "p":
            parts.append("مبني للمجهول")
        mood = {"s": "SUBJ", "j": "JUS"}.get(a.get("mod"))
        if mood and asp == "IMPF":
            parts.append(MOOD[mood])
        parts.append(("رباعي مجرد" if vf == 1 else "رباعي مزيد") if root_len == 4 else FORM_LABEL.get(vf, ""))
        return " — ".join(p for p in parts if p), vf, "V", asp
    head = {"adj": "صفة", "noun_prop": "علم"}.get(pos, "اسم")
    parts = [head]
    bw = a.get("bw") or ""
    num, gen = a.get("num"), a.get("gen")
    if num == "d":
        parts.append("مثنى")
    elif num == "p":
        parts.append("جمع مذكر سالم" if "NSUFF_MASC_PL" in bw else
                     "جمع مؤنث سالم" if "NSUFF_FEM_PL" in bw else "جمع تكسير")
    elif gen == "f":
        parts.append("مؤنث")
    return " — ".join(parts), 1, "N", None


_LETTER = re.compile("[ء-يٱ]")


def vf_from_wazn(wazn: str) -> int:
    """باب of a verb from its ميزان: letters + shadda, affixes allowed around."""
    s = re.sub("[ً-ِْ]", "", wazn).replace("أ", "ا").replace("ٱ", "ا")
    for pat, vf in (("ستفعل", 10), ("نفعل", 7), ("فتعل", 8), ("تفاعل", 6), ("تفعّل", 5),
                    ("فاعل", 3), ("فعّل", 2)):
        if pat in s:
            return vf
    # أَفْعَلَ / يُفْعِلُ: a hamza or a ḍamma-voweled prefix before a voweless ف
    if re.search("^(أَ|[يتنأ]ُ)فْ", wazn.replace("ٱ", "ا")):
        return 4
    return 1


def token_span(a, token: str):
    """The hadith's own spelling of the analysis' kept segments, split as
    (word, subject suffix) — or None if the letters don't line up."""
    segs = [s.rsplit("/", 1) for s in (a.get("bw") or "").split("+") if "/" in s]
    counts = [(len(_LETTER.findall(f)), t) for f, t in segs]
    units = []
    for ch in token:
        if _LETTER.match(ch):
            units.append(ch)
        elif units:
            units[-1] += ch
    if sum(n for n, _ in counts) != len(units):
        return None
    word, suffix, i = "", "", 0
    for n, tag in counts:
        part = "".join(units[i:i + n])
        i += n
        if not KEEP_TAGS.match(tag) or tag.startswith("CASE"):
            continue
        if SUBJ.match(tag):
            suffix += part
        else:
            word += part
    return word, suffix


# خ-49: lemmas with قلب مكاني (see build_wazn.QALB_LEMMAS) — for the committee.
QALB_LEX = {"طاغوت"}


def weigh_camel(a, root_letters: str, token: str | None = None):
    """(label, wazn, lemma_wazn, status) for one CAMeL analysis. When the
    analysis contradicts the hadith's own vowels (score < 0 — the light CAMeL
    DB lacks e.g. حَدَّثَ), the hadith's spelling is weighed instead and the
    verb's باب is read off that ميزان."""
    from build_wazn import weigh, verb_bab
    if re.sub("[ً-ٰٕ]", "", a.get("lex") or "") in QALB_LEX:
        label = camel_label(a, 1, len(root_letters))[0]
        return label, None, None, "review"
    if token and (a.get("score") or 0) < 0:
        span = token_span(a, token)
        if span and span[0]:
            root_bw = "".join(ROOT_BW.get(ch, to_bw(ch)) for ch in root_letters)
            label, vf, pos, asp = camel_label(a, 1, len(root_letters))
            plural = ("MP" if a.get("gen") != "f" else "FP") if pos == "N" and a.get("num") == "p" else None
            wazn, status = None, "review"
            for try_vf in (1, 8, 4):
                wazn, status = weigh(to_bw(span[0]), root_bw, vf=try_vf, pos_tag=pos, aspect=asp,
                                     suffix_bw=to_bw(span[1]), plural_ending=plural)
                if wazn:
                    break
            if wazn:
                if pos == "V":
                    vf = vf_from_wazn(wazn)
                    label = camel_label(a, vf, len(root_letters))[0]
                    return label, wazn, verb_bab(None, root_bw, vf), status
                wazn = re.sub("[ً-ِ]+$", "", wazn)
                lemma_wazn, _ = weigh(to_bw(a.get("lex") or ""), root_bw, vf=1, pos_tag=pos)
                return label, wazn, lemma_wazn, status
    segs = [s.rsplit("/", 1) for s in (a.get("bw") or "").split("+") if "/" in s]
    word, suffix, seen_stem = "", "", False
    for form, tag in segs:
        if not KEEP_TAGS.match(tag) or tag.startswith("CASE"):
            continue
        if SUBJ.match(tag):
            suffix += form
        elif tag.startswith("NSUFF"):
            word += form
        else:
            word += form
            seen_stem = seen_stem or tag in ("PV", "IV", "CV") or tag.startswith(("NOUN", "ADJ", "PV_", "IV_"))
    if not seen_stem or not word:
        return None
    root_bw = "".join(ROOT_BW.get(ch, to_bw(ch)) for ch in root_letters)
    lex_bw = to_bw(a.get("lex") or "")
    vf = verb_form_from_lex(lex_bw) if a.get("pos") == "verb" else 1
    label, vf, pos, asp = camel_label(a, vf, len(root_letters))
    plural = None
    if pos == "N" and a.get("num") == "p":
        plural = "MP" if a.get("gen") != "f" else "FP"
    wazn, status = None, "review"
    for try_vf in dict.fromkeys((vf, 8, 4, 1)):
        wazn, status = weigh(to_bw(word), root_bw, vf=try_vf, pos_tag=pos, aspect=asp,
                             suffix_bw=to_bw(suffix), plural_ending=plural)
        if wazn:
            break
    if wazn and pos != "V":
        wazn = re.sub("[ً-ِ]+$", "", wazn)
    if pos == "V":
        lemma_wazn = verb_bab(None, root_bw, vf)
    else:
        lemma_wazn, _ = weigh(lex_bw, root_bw, vf=1, pos_tag=pos)
    return label, wazn, lemma_wazn, status


def build_hadith_morph(cur) -> Counter:
    """Fill hadith_word_morph for every hadith word linked to a root."""
    from build_hadith import light_key
    from build_wazn import plain
    # Quran: (light form, root) → most frequent (label, wazn, lemma wazn)
    qidx: dict = defaultdict(Counter)
    for text, rid, lab, wz, lwz in cur.execute(
            "SELECT w.text, w.root_id, m.label_id, m.wazn_id, m.lemma_wazn_id FROM words w "
            "JOIN word_morph m USING (surah_no, ayah_no, word_no) "
            "WHERE w.root_id IS NOT NULL AND m.wazn_id IS NOT NULL"):
        qidx[(light_key(text.split(" ")[0]), rid)][(lab, wz, lwz)] += 1
    qbest = {k: c.most_common(1)[0][0] for k, c in qidx.items()}

    camel = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    labels = dict(cur.execute("SELECT text, id FROM morph_label"))
    wazns = {(t, l): i for i, t, l in cur.execute("SELECT id, text, is_lemma FROM wazn")}
    new_labels, new_wazns = [], []

    def label_id(t):
        if not t:
            return None
        if t not in labels:
            labels[t] = max(labels.values(), default=0) + 1
            new_labels.append((labels[t], t))
        return labels[t]

    def wazn_id(t, lem):
        if not t:
            return None
        if (t, lem) not in wazns:
            wazns[(t, lem)] = max(wazns.values(), default=0) + 1
            new_wazns.append((wazns[(t, lem)], t, plain(t), lem))
        return wazns[(t, lem)]

    letters = dict(cur.execute("SELECT root_id, letters FROM roots"))
    toks = {hid: t.split(" ") for hid, t in cur.execute("SELECT hid, tokens FROM hadith")}
    rows, stats, memo = [], Counter(), {}
    for rid, hid, wn in cur.execute("SELECT root_id, hid, word_no FROM hadith_root_words").fetchall():
        w = toks[hid][wn - 1]
        key = (light_key(w), rid)
        if key in qbest:
            lab, wz, lwz = qbest[key]
            rows.append((hid, wn, lab, wz, lwz, 1))
            stats["quran"] += 1
            continue
        a = camel.get(f"{w}|{rid}")
        if a is None:
            stats["none"] += 1
            continue
        mk = (w, rid)
        if mk not in memo:
            r = weigh_camel(a, letters[rid], w)
            memo[mk] = None if r is None else (label_id(r[0]), wazn_id(r[1], 0), wazn_id(r[2], 1))
        r = memo[mk]
        if r is None or r[1] is None:
            stats["review"] += 1
            if r is not None:
                rows.append((hid, wn, r[0], None, r[2], 2))
            continue
        rows.append((hid, wn, *r, 2))
        stats["camel"] += 1
    cur.executemany("INSERT INTO morph_label (id, text) VALUES (?,?)", new_labels)
    cur.executemany("INSERT INTO wazn (id, text, plain, is_lemma) VALUES (?,?,?,?)", new_wazns)

    # The app needs, per ميزان, which roots and word forms occur in the hadith —
    # not every word (that would add ~5 MB). Ship the aggregate only.
    cur.executescript("""
        DROP TABLE IF EXISTS hadith_word_morph;
        CREATE TABLE IF NOT EXISTS hadith_wazn_forms (
            wazn_id INTEGER NOT NULL,
            root_id INTEGER NOT NULL,
            n INTEGER NOT NULL,          -- hadith words of this root on this ميزان
            forms TEXT NOT NULL,         -- most frequent forms: «form:count|form:count» (≤ 6)
            PRIMARY KEY (wazn_id, root_id)
        ) WITHOUT ROWID;
    """)
    root_of = {(hid, wn): rid for rid, hid, wn in cur.execute(
        "SELECT root_id, hid, word_no FROM hadith_root_words")}
    agg: dict = defaultdict(Counter)
    for hid, wn, _lab, wz, lwz, _src in rows:
        form = toks[hid][wn - 1]
        for wid in (wz, lwz):
            if wid is not None:
                agg[(wid, root_of[(hid, wn)])][form] += 1
    cur.executemany("INSERT INTO hadith_wazn_forms VALUES (?,?,?,?)", [
        (wid, rid, sum(c.values()), "|".join(f"{f}:{n}" for f, n in c.most_common(6)))
        for (wid, rid), c in agg.items()])
    # hadith counts per ميزان (Quran counts stay in n_words / n_roots)
    cols = {r[1] for r in cur.execute("PRAGMA table_info(wazn)")}
    if "n_hadith" not in cols:
        cur.execute("ALTER TABLE wazn ADD COLUMN n_hadith INTEGER NOT NULL DEFAULT 0")
    cur.execute("""UPDATE wazn SET n_hadith = COALESCE((SELECT SUM(n) FROM hadith_wazn_forms h
                   WHERE h.wazn_id = wazn.id), 0)""")
    return stats


if __name__ == "__main__":
    if "--camel" in sys.argv:
        sys.path.insert(0, str(HERE))
        camel_step()
