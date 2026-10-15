"""
Build Quranic synonyms for roots that do NOT occur in the Quran.

Source: Wiktionary Arabic dump (kaikki.org, CC BY-SA), cached at
    cache/kaikki-ar.jsonl.gz
Each non-Quranic root gets an English gloss bag from Wiktionary; each Quranic
root already has one from the QAC dictionary (cache/qac_glosses.json). Both are
stemmed into the same token space and compared by TF-IDF cosine, so e.g.
    نجح ("to succeed")  →  فلح ("successful"), فوز ("success")

USAGE
    python build_external_synonyms.py          # full build
    python build_external_synonyms.py sample   # print samples only

OUTPUT
    cache/external_synonyms.json
        { root: {"gloss": "...", "syn": [[quranic_root, score], ...]} }
build_db.py reads this file (when present) to fill the external_* tables.
"""

from __future__ import annotations

import gzip
import json
import math
import re
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"
KAIKKI = CACHE / "kaikki-ar.jsonl.gz"
QAC = CACHE / "qac_glosses.json"
OUT = CACHE / "external_synonyms.json"
DB_PATH = ROOT.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"

TOP_N = 10
MIN_SCORE = 0.08
REL_CUTOFF = 0.30

# --- Arabic root normalization (matches the DB: every hamza form → ا) -------

_AR_LETTER = re.compile(r"[ء-يٱ]")
_ROOT_FOLD = {"ء": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ؤ": "ا", "ئ": "ا",
              "ٱ": "ا", "ى": "ي", "ة": "ه"}


def norm_root(text: str) -> str:
    letters = [ch for ch in text if _AR_LETTER.match(ch)]
    return "".join(_ROOT_FOLD.get(ch, ch) for ch in letters)


# --- English tokenization + light stemming ---------------------------------

_WORD = re.compile(r"[a-z]+")
_STOP = set("""
a about above after again against all also am an and any are as at be been
being below between both but by can could did do does doing down during each
few for from further had has have having he her here him his how i if in into
is it its itself just me more most my no nor not now of off on once only or
other our out over own same she should so some such than that the their them
then there these they this those through to too under until up very was we
were what when where which while who whom why will with would you your
thee thou thy ye unto upon thus o
noun verb adjective adverb participle active passive form plural singular
feminine masculine verbal perfect imperfect dual collective diminutive
elative particle pronoun preposition conjunction interjection proper
nominal iii vii viii xii
someone something oneself one ones thing things person people
become get make cause let put give take go come be have do
alternative spelling obsolete archaic dialectal literally figuratively
especially usually often certain kind sort type
""".split())

# Glosses that only point at another entry carry no meaning of their own.
_REFERENCE_GLOSS = re.compile(
    r"^(verbal noun|plural|feminine|masculine|dual|singulative|collective|"
    r"diminutive|elative|alternative form|alternative spelling|"
    r"active participle|passive participle|nisba|form [ivx]+) of\b"
)

_SUFFIXES = ["fulness", "ousness", "iveness", "ational", "ations", "ation",
             "ments", "ment", "ness", "ings", "ing", "edly", "ful", "ers",
             "ied", "ies", "ed", "er", "ly", "es", "s"]


def stem(word: str) -> str:
    w = word
    if w.endswith("our") and len(w) > 5:  # labour → labor
        w = w[:-3] + "or"
    for suf in _SUFFIXES:
        if not w.endswith(suf):
            continue
        if suf == "s" and (w.endswith("ss") or w.endswith("us")):
            break
        base = w[: -len(suf)]
        if suf in ("ied", "ies"):
            base += "y"
        if len(base) >= 3:
            w = base
        break
    return w


# A word right after one of these is negated ("lose courage" ≠ "courage"),
# so antonyms stop matching as synonyms.
_NEGATORS = {"not", "no", "without", "lose", "lost", "loses", "losing",
             "lack", "lacks", "lacking", "never"}


def tokens(text: str) -> list[str]:
    return tokens_from_words(_WORD.findall(text.lower()))


def tokens_from_words(words: list[str]) -> list[str]:
    out = []
    negate = False
    negated_raw = None  # QAC repeats a heading word ×3; keep the whole run negated
    for raw in words:
        if raw in _NEGATORS:
            negate = True
            continue
        if len(raw) < 3 or raw in _STOP:
            continue
        s = stem(raw)
        if len(s) < 3 or s in _STOP:
            continue
        if negate or raw == negated_raw:
            out.append("no_" + s)
            negated_raw = raw
            negate = False
        else:
            out.append(s)
            negated_raw = None
    return out


# --- Wiktionary parsing -----------------------------------------------------

def _root_from_templates(entry: dict) -> str | None:
    for t in entry.get("etymology_templates", []) + entry.get("head_templates", []):
        if t.get("name") in ("ar-root", "ar-rootbox"):
            args = t.get("args", {})
            joined = " ".join(v for k, v in sorted(args.items()) if k.isdigit())
            r = norm_root(joined)
            if 3 <= len(r) <= 4:
                return r
    return None


def _verb_root(entry: dict) -> tuple[str, int] | None:
    """Root from a Form I or Form II verb headword (both keep exactly the three
    root letters; II only adds a shadda). Returns (root, priority) where Form I
    is preferred for the display gloss."""
    if entry.get("pos") != "verb":
        return None
    for t in entry.get("head_templates", []):
        if t.get("name") != "ar-verb":
            continue
        form = t.get("args", {}).get("1", "").split("/")[0]
        if form in ("I", "II"):
            r = norm_root(entry.get("word", ""))
            if len(r) == 3:
                return r, (0 if form == "I" else 2)
    return None


def parse_wiktionary() -> tuple[dict[str, Counter], dict[str, str]]:
    """Return (root → token Counter, root → short display gloss)."""
    bags: dict[str, Counter] = defaultdict(Counter)
    display: dict[str, tuple[int, str]] = {}  # root → (priority, gloss)
    with gzip.open(KAIKKI, "rt", encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            root = _root_from_templates(e)
            priority = 1
            if root is None:
                found = _verb_root(e)
                if found is None:
                    continue
                root, priority = found  # Form I gloss is the best display gloss
            glosses = [g for s in e.get("senses", []) for g in s.get("glosses", [])]
            glosses = [g for g in glosses if not _REFERENCE_GLOSS.match(g.lower())]
            if not glosses:
                continue
            for g in glosses:
                bags[root].update(tokens(g))
            short = glosses[0][:80]
            if root not in display or priority < display[root][0]:
                display[root] = (priority, short)
    return bags, {r: g for r, (_p, g) in display.items()}


# --- Similarity -------------------------------------------------------------

def tfidf(bags: dict[str, Counter], idf: dict[str, float]) -> dict[str, dict[str, float]]:
    vecs = {}
    for r, bag in bags.items():
        total = sum(bag.values())
        v = {t: (c / total) * idf[t] for t, c in bag.items() if t in idf}
        norm = math.sqrt(sum(x * x for x in v.values()))
        if norm > 0:
            vecs[r] = {t: x / norm for t, x in v.items()}
    return vecs


def main(sample_only: bool) -> int:
    conn = sqlite3.connect(DB_PATH)
    quranic = {r for (r,) in conn.execute("SELECT letters FROM roots")}
    conn.close()

    qac_raw = json.loads(QAC.read_text(encoding="utf-8"))
    q_bags: dict[str, Counter] = {}
    for r, toks in qac_raw.items():
        # QAC tokens are an ordered flat list, so negators still precede the
        # word they negate ("lost", "courage" → no_courag).
        bag = Counter(tokens_from_words([t.lower() for t in toks]))
        if bag:
            q_bags[r] = bag

    print("Parsing Wiktionary…")
    w_bags, w_gloss = parse_wiktionary()
    external = {r: b for r, b in w_bags.items() if r not in quranic and b}
    print(f"  Wiktionary roots: {len(w_bags):,}  |  not in Quran: {len(external):,}")

    # Shared IDF over both corpora so generic words are down-weighted alike.
    df = Counter()
    for bag in list(q_bags.values()) + list(external.values()):
        df.update(bag.keys())
    n_docs = len(q_bags) + len(external)
    idf = {t: math.log(n_docs / d) for t, d in df.items() if d < n_docs * 0.5}

    q_vecs = tfidf(q_bags, idf)
    e_vecs = tfidf(external, idf)

    postings: dict[str, list[tuple[str, float]]] = defaultdict(list)
    for r, v in q_vecs.items():
        for t, w in v.items():
            postings[t].append((r, w))

    result = {}
    for er, ev in e_vecs.items():
        scores: Counter = Counter()
        for t, w in ev.items():
            for qr, qw in postings.get(t, ()):
                scores[qr] += w * qw
        ranked = scores.most_common(TOP_N)
        # Absolute floor + relative floor: drop a tail far weaker than the best.
        floor = max(MIN_SCORE, REL_CUTOFF * ranked[0][1]) if ranked else MIN_SCORE
        syn = [[qr, round(s, 4)] for qr, s in ranked if s >= floor]
        # Keep every non-Quranic root that has a meaning — even without Quranic
        # synonyms it is needed for التقاليب («عربي غير قرآني» + its meaning).
        if syn or w_gloss.get(er):
            result[er] = {"gloss": w_gloss.get(er, ""), "syn": syn}

    print(f"  non-Quranic roots kept: {len(result):,}  "
          f"(with ≥1 Quranic synonym: {sum(1 for v in result.values() if v['syn']):,})")

    # Glosses of Quranic roots too (fallback meaning when QAC has none).
    quranic_gloss = {r: w_gloss[r] for r in quranic if w_gloss.get(r)}
    (CACHE / "wiktionary_quranic_glosses.json").write_text(
        json.dumps(quranic_gloss, ensure_ascii=False), encoding="utf-8")

    for probe in ["نجح", "تعب", "قلق", "خجل", "شجع", "حمق", "وجع", "ذكي"]:
        info = result.get(probe)
        if info:
            print(f"  {probe} ({info['gloss']}): {[(s, round(v, 2)) for s, v in info['syn'][:6]]}")
        else:
            print(f"  {probe}: —")

    if not sample_only:
        OUT.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sample_only=len(sys.argv) > 1 and sys.argv[1] == "sample"))
