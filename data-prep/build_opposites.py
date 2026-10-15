"""
Build Quranic التقابل (opposite) root pairs.

Sources (in trust order):
  curated     opposites_curated.json — hand-picked Quranic طباق pairs
  wiktionary  antonyms recorded in the Wiktionary Arabic dump
  wordnet     English antonyms (WordNet) between the core QAC glosses of two
              roots — only for roots that co-occur in ≥ MIN_CO ayahs, since
              Quranic التقابل is juxtaposition within an ayah

USAGE
    python build_opposites.py          # write cache/opposites.json
    python build_opposites.py sample   # print automatic pairs only
"""

from __future__ import annotations

import gzip
import json
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

from nltk.corpus import wordnet as wn

import build_external_synonyms as bes

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"
DB_PATH = ROOT.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"
OUT = CACHE / "opposites.json"

MIN_CO = 2          # ayahs shared, for automatic (non-curated) pairs
CORE_WORDS = 3      # only a root's dominant meanings may form an opposition

# Light, highly polysemous words whose WordNet antonyms come from the wrong
# sense ("lie" = recline ↔ "stand"; "make" ↔ "break"), producing false pairs.
POLYSEMOUS = set("""
give take make break stand lie lies rest change get put set keep hold bring
leave carry turn run move hire
""".split())


def cooccurrence(conn) -> Counter:
    ayah_roots: dict[tuple, set] = defaultdict(set)
    for s, a, r in conn.execute(
            "SELECT w.surah_no, w.ayah_no, r.letters FROM words w "
            "JOIN roots r ON r.root_id = w.root_id"):
        ayah_roots[(s, a)].add(r)
    co: Counter = Counter()
    for rs in ayah_roots.values():
        rl = sorted(rs)
        for i in range(len(rl)):
            for j in range(i + 1, len(rl)):
                co[(rl[i], rl[j])] += 1
    return co


def core_words(qac: dict) -> dict[str, set[str]]:
    """Most frequent content words of each root's QAC gloss, lemmatized."""
    out = {}
    for r, toks in qac.items():
        words = [t.lower() for t in toks
                 if len(t) >= 3 and t.lower() not in bes._STOP
                 and t.lower() not in bes._NEGATORS]
        # Collapse inflections (know/knows/knew) before ranking, so the top-N
        # are distinct meanings rather than one meaning's forms.
        lemma_counts: Counter = Counter()
        for w in words:
            lemma_counts[wn.morphy(w) or w] += 1
        out[r] = {w for w, _ in lemma_counts.most_common(CORE_WORDS)
                  if w not in POLYSEMOUS}
    return out


_ant_cache: dict[str, set[str]] = {}


def antonyms(word: str) -> set[str]:
    if word not in _ant_cache:
        _ant_cache[word] = {a.name().lower()
                            for s in wn.synsets(word)
                            for l in s.lemmas()
                            for a in l.antonyms()}
    return _ant_cache[word]


def wiktionary_pairs(quranic: set[str]) -> set[tuple[str, str]]:
    pairs = set()
    with gzip.open(CACHE / "kaikki-ar.jsonl.gz", "rt", encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            ants = e.get("antonyms", []) + [a for s in e.get("senses", [])
                                            for a in s.get("antonyms", [])]
            if not ants:
                continue
            found = bes._root_from_templates(e) or (bes._verb_root(e) or (None,))[0]
            r1 = found or bes.norm_root(e.get("word", ""))
            for a in ants:
                r2 = bes.norm_root(a.get("word", ""))
                if r1 in quranic and r2 in quranic and r1 != r2:
                    pairs.add(tuple(sorted((r1, r2))))
    return pairs


def main(sample_only: bool) -> int:
    conn = sqlite3.connect(DB_PATH)
    quranic = {r for (r,) in conn.execute("SELECT letters FROM roots")}
    co = cooccurrence(conn)
    conn.close()

    curated = json.loads((ROOT / "opposites_curated.json").read_text(encoding="utf-8"))["pairs"]
    result: dict[tuple, dict] = {}
    for a, b, label in curated:
        key = tuple(sorted((a, b)))
        result[key] = {"a": key[0], "b": key[1], "label": label,
                       "source": "curated", "co": co[key]}

    for key in wiktionary_pairs(quranic):
        if key not in result and co[key] >= 1:
            result[key] = {"a": key[0], "b": key[1], "label": "",
                           "source": "wiktionary", "co": co[key]}

    qac = json.loads((CACHE / "qac_glosses.json").read_text(encoding="utf-8"))
    cores = core_words(qac)
    auto = []
    for (a, b), n in co.items():
        if n < MIN_CO or (a, b) in result or a not in cores or b not in cores:
            continue
        hits = [(x, y) for x in cores[a] for y in cores[b] if y in antonyms(x)]
        if hits:
            auto.append(((a, b), n, hits))
    for (a, b), n, hits in auto:
        result[(a, b)] = {"a": a, "b": b, "label": f"{hits[0][0]} ↔ {hits[0][1]}",
                          "source": "wordnet", "co": n}

    by_src = Counter(v["source"] for v in result.values())
    print(f"pairs: {len(result)}  {dict(by_src)}")
    auto.sort(key=lambda x: -x[1])
    for (a, b), n, hits in auto[:40]:
        print(f"  {a} ↔ {b}  co={n}  {hits[:2]}")

    if not sample_only:
        OUT.write_text(json.dumps(list(result.values()), ensure_ascii=False, indent=1),
                       encoding="utf-8")
        print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sample_only=len(sys.argv) > 1 and sys.argv[1] == "sample"))
