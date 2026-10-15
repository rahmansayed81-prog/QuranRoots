"""
Scrape almaany.com for Arabic synonyms of every Quranic lemma, then map the
synonyms back to roots so we can populate a high-quality semantic-similarity
table in the offline DB.

USAGE
-----
1) Probe (only 3 roots, verbose, no rate limit) — verifies HTML parsing works:
       python fetch_almaany.py probe

2) Full run (all roots, ~1.5s delay between requests, resumable):
       python fetch_almaany.py full

OUTPUT
------
   cache/almaany_html/<lemma>.html          (raw page, for re-parsing)
   cache/almaany_synonyms.json              (extracted synonyms per lemma)

After running, run `python build_db.py` again to regenerate the DB; the build
script will read the JSON if it exists and build `root_synonym` accordingly.

POLITENESS
----------
- 1.5 s delay between requests
- Honest User-Agent identifying the bot
- Resumable: re-running skips lemmas already in the JSON cache
- If almaany returns 429 or 5xx, the script backs off (30 s) and retries once
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from html import unescape
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"
HTML_DIR = CACHE / "almaany_html"
OUT_JSON = CACHE / "almaany_synonyms.json"
DB_PATH = ROOT.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"

HTML_DIR.mkdir(parents=True, exist_ok=True)

USER_AGENT = "QuranRootsBot/1.0 (offline app; contact: github.com/quranroots)"
BASE_URL = "https://www.almaany.com/ar/dict/ar-ar/"
DELAY_SEC = 1.5
TIMEOUT = 30

# Arabic diacritics + Quranic marks we should strip before querying almaany.
_DIACRITICS = re.compile("[ً-ْٰـۖ-ۭٱ]")
# All non-letter chars (keeps Arabic letters only)
_KEEP_LETTERS = re.compile(r"[^ء-ي]")


def normalize_for_lookup(word: str) -> str:
    """Strip diacritics, tatweel, alif-wasla, recitation marks; keep just
    the basic Arabic letters."""
    no_dia = _DIACRITICS.sub("", word)
    return _KEEP_LETTERS.sub("", no_dia).strip()


# --- HTML parsing -----------------------------------------------------------

# Almaany page structure: each dictionary section is rendered as a card. The
# synonym/antonym blocks appear as plain Arabic prose between span/div tags.
# We use multiple complementary patterns and accept any that fire.

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def strip_html(html: str) -> str:
    return _WS_RE.sub(" ", unescape(_TAG_RE.sub(" ", html))).strip()


# Likely section markers in almaany pages.
SECTION_MARKERS = [
    "ترادف",
    "مرادف",
    "المرادفات",
    "المعنى",
    "(انظر:",  # cross-reference marker
    "الجمع",   # plural form
]

# A "synonym line" typically looks like a comma-separated list of single
# Arabic words inside a dictionary card body.
_ARABIC_WORD_RE = re.compile(r"[ء-ي]{2,}")


def extract_synonyms(html: str) -> list[str]:
    """Best-effort: pull tokens that appear near 'مرادف/ترادف' markers, plus
    any short Arabic-only tokens inside <a> tags within result cards (those
    are usually cross-references)."""
    text = strip_html(html)
    found: list[str] = []

    # 1) Cross-reference tokens: short Arabic links between bullets like
    #    "(انظر: كَتَبَ)" — almaany emits these for synonym pointers.
    for m in re.finditer(r"\(\s*انظر\s*:\s*([^)]+)\)", text):
        for w in _ARABIC_WORD_RE.findall(m.group(1)):
            found.append(w)

    # 2) After-marker tokens. For each marker, grab the next ~120 chars and
    #    pull Arabic words from there (stopping at the next ":" header).
    for marker in SECTION_MARKERS:
        for m in re.finditer(re.escape(marker), text):
            tail = text[m.end(): m.end() + 200]
            # Stop at next obvious section break.
            tail = re.split(r"[•:؛\n]| - |\bو$", tail, maxsplit=1)[0]
            for w in _ARABIC_WORD_RE.findall(tail):
                if len(w) >= 2:
                    found.append(w)

    # 3) Anchor tags inside the result body (almaany wraps the answer area in
    #    a card; cross-links between entries are <a href="/ar/dict/ar-ar/...">).
    for m in re.finditer(
        r'<a[^>]*href="/ar/dict/ar-ar/([^"/?]+)/?"[^>]*>', html
    ):
        candidate = urllib.parse.unquote(m.group(1))
        cand = normalize_for_lookup(candidate)
        if cand and 2 <= len(cand) <= 10:
            found.append(cand)

    # De-duplicate, normalize.
    seen: set[str] = set()
    out: list[str] = []
    for w in found:
        n = normalize_for_lookup(w)
        if n and n not in seen and len(n) >= 2:
            seen.add(n)
            out.append(n)
    return out


# --- DB helpers -------------------------------------------------------------

def load_lemmas_per_root() -> dict[str, list[str]]:
    """Return {root_letters: [most_common_lemma, second_most, ...]}.
    Limits to top 3 lemmas per root to bound runtime."""
    conn = sqlite3.connect(DB_PATH)
    rows = conn.execute(
        "SELECT r.letters, w.lemma "
        "FROM roots r JOIN words w ON w.root_id = r.root_id "
        "WHERE w.lemma IS NOT NULL"
    ).fetchall()
    conn.close()
    counts: defaultdict[str, Counter] = defaultdict(Counter)
    for letters, lemma in rows:
        n = normalize_for_lookup(lemma)
        if n:
            counts[letters][n] += 1
    return {
        root: [w for w, _ in c.most_common(3)]
        for root, c in counts.items()
    }


# --- HTTP ------------------------------------------------------------------

def fetch_page(lemma: str, html_path: Path) -> str | None:
    if html_path.exists() and html_path.stat().st_size > 200:
        return html_path.read_text(encoding="utf-8", errors="ignore")
    url = BASE_URL + urllib.parse.quote(lemma) + "/"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                if resp.status != 200:
                    raise RuntimeError(f"HTTP {resp.status}")
                data = resp.read().decode("utf-8", errors="ignore")
            if "<html" not in data.lower():
                return None
            html_path.write_text(data, encoding="utf-8")
            return data
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504):
                print(f"    backoff {e.code} on {lemma}, sleeping 30s")
                time.sleep(30)
                continue
            return None
        except Exception as e:
            print(f"    error {lemma}: {e}")
            return None
    return None


# --- Main flows -------------------------------------------------------------

def run(mode: str) -> int:
    if not DB_PATH.exists():
        print(f"DB not found at {DB_PATH}. Run build_db.py first.")
        return 1

    lemmas_per_root = load_lemmas_per_root()
    print(f"Loaded {len(lemmas_per_root)} roots, "
          f"{sum(len(v) for v in lemmas_per_root.values())} lemma queries.")

    # Resume state
    out: dict[str, list[str]] = {}
    if OUT_JSON.exists():
        out = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        print(f"Resuming: {len(out)} lemmas already fetched.")

    items = []
    for root, lemmas in lemmas_per_root.items():
        for lemma in lemmas:
            items.append((root, lemma))

    if mode == "probe":
        items = items[:3]
        print("\n--- PROBE MODE (3 lemmas, verbose) ---\n")

    total = len(items)
    processed = 0
    for root, lemma in items:
        processed += 1
        if lemma in out:
            continue
        html_path = HTML_DIR / f"{lemma}.html"
        html = fetch_page(lemma, html_path)
        if html is None:
            out[lemma] = []
            print(f"  [{processed}/{total}] {lemma}  FAIL")
        else:
            syns = extract_synonyms(html)
            out[lemma] = syns
            if mode == "probe" or processed % 50 == 0:
                print(f"  [{processed}/{total}] {lemma}  -> {len(syns)} syns: {syns[:8]}")

        # Persist every 25 fetches so we don't lose progress.
        if processed % 25 == 0:
            OUT_JSON.write_text(
                json.dumps(out, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

        if mode == "full":
            time.sleep(DELAY_SEC)

    OUT_JSON.write_text(
        json.dumps(out, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"\nDone. Wrote {OUT_JSON} ({len(out)} entries).")
    if mode == "probe":
        print("\nReview the synonyms above. If they look reasonable, run:")
        print("    python fetch_almaany.py full")
        print("Otherwise share a sample HTML at:", HTML_DIR)
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    if mode not in ("probe", "full"):
        print("usage: fetch_almaany.py [probe|full]")
        sys.exit(2)
    sys.exit(run(mode))
