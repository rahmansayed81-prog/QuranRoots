"""
Fetch QAC (corpus.quran.com) per-root dictionary pages and extract their
English glosses. The output JSON feeds the semantic-similarity computation
in build_db.py — replacing the noisier ayah-level Sahih signal with tight,
root-specific gloss bags.

USAGE
-----
    python fetch_qac_glosses.py probe   # 5 roots, verbose
    python fetch_qac_glosses.py full    # all ~1,642 roots, resumable, ~40 min

OUTPUT
------
    cache/qac_glosses.json       {root_letters_arabic: [token, token, ...]}
    cache/qac_html/<root>.html   raw pages, for re-parsing without re-fetching

After running, re-run `python build_db.py` to rebuild the DB using the new
gloss data.
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from html import unescape
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "cache"
HTML_DIR = CACHE / "qac_html"
OUT_JSON = CACHE / "qac_glosses.json"
DB_PATH = ROOT.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"

HTML_DIR.mkdir(parents=True, exist_ok=True)

USER_AGENT = "QuranRootsBot/1.0 (offline Quran-study app build)"
BASE_URL = "https://corpus.quran.com/qurandictionary.jsp"
DELAY_SEC = 1.5
TIMEOUT = 30

# Inverse of the Buckwalter table used in build_db.py — Arabic → Buckwalter.
AR2BW = {
    "ء": "'", "آ": "|", "أ": ">", "ؤ": "&", "إ": "<", "ئ": "}",
    "ا": "A", "ب": "b", "ة": "p", "ت": "t", "ث": "v", "ج": "j",
    "ح": "H", "خ": "x", "د": "d", "ذ": "*", "ر": "r", "ز": "z",
    "س": "s", "ش": "$", "ص": "S", "ض": "D", "ط": "T", "ظ": "Z",
    "ع": "E", "غ": "g", "ـ": "_", "ف": "f", "ق": "q", "ك": "k",
    "ل": "l", "م": "m", "ن": "n", "ه": "h", "و": "w", "ى": "Y",
    "ي": "y", "ٱ": "{",
}


def arabic_to_buckwalter(letters: str) -> str:
    return "".join(AR2BW.get(c, c) for c in letters)


# --- HTML parsing -----------------------------------------------------------

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"[A-Za-z']+")

# Curated stopwords (kept identical-spirit to build_db's English list).
_STOPWORDS = set("""
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
""".split())


def _strip(html: str) -> str:
    return _WS_RE.sub(" ", unescape(_TAG_RE.sub(" ", html))).strip()


# Patterns for the two valuable content blocks.
_DXE_RE = re.compile(r'<h4 class="dxe">([^<]+)</h4>')
_C2_RE = re.compile(r'<td class="c2">(?:<a[^>]*>)?([^<]+)(?:</a>)?</td>')


def extract_tokens(html: str) -> list[str]:
    """Extract English-word tokens from QAC dictionary HTML. Combines:
       - General form glosses from `<h4 class="dxe">`
       - Per-occurrence glosses from `<td class="c2">` cells
       Returns a *flat list* of tokens (duplicates kept — they signal frequency)."""
    tokens: list[str] = []

    for m in _DXE_RE.finditer(html):
        # E.g., "Verb (form I) -to know"  → keep "know"
        # Drop everything up to the first '-' or ':' (POS prefix).
        raw = m.group(1)
        if "-" in raw:
            raw = raw.split("-", 1)[1]
        for w in _WORD_RE.findall(raw.lower()):
            w = w.strip("'")
            if len(w) >= 3 and w not in _STOPWORDS:
                # Weight general glosses higher by emitting 3 copies.
                tokens.extend([w] * 3)

    for m in _C2_RE.finditer(html):
        raw = m.group(1)
        for w in _WORD_RE.findall(raw.lower()):
            w = w.strip("'")
            if len(w) >= 3 and w not in _STOPWORDS:
                tokens.append(w)

    return tokens


# --- DB ---------------------------------------------------------------------

def load_roots() -> list[str]:
    """Return the Arabic letters of every root in the DB, ordered by frequency
    desc so common roots get fetched first (and a partial run is still useful)."""
    conn = sqlite3.connect(DB_PATH)
    rows = conn.execute(
        "SELECT letters FROM roots ORDER BY occurrences DESC"
    ).fetchall()
    conn.close()
    return [r[0] for r in rows]


# --- HTTP -------------------------------------------------------------------

def fetch_page(buckwalter: str, html_path: Path) -> str | None:
    if html_path.exists() and html_path.stat().st_size > 500:
        return html_path.read_text(encoding="utf-8", errors="ignore")
    url = f"{BASE_URL}?q={urllib.parse.quote(buckwalter)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for _ in range(2):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                data = resp.read().decode("utf-8", errors="ignore")
            if "qurandictionary" not in data.lower() and "occurs" not in data.lower():
                return None
            html_path.write_text(data, encoding="utf-8")
            return data
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504):
                print(f"    backoff {e.code}, sleeping 30s")
                time.sleep(30)
                continue
            return None
        except Exception as e:
            print(f"    error: {e}")
            return None
    return None


# --- Main -------------------------------------------------------------------

def run(mode: str) -> int:
    if not DB_PATH.exists():
        print(f"DB not found at {DB_PATH}. Run build_db.py first.")
        return 1

    roots = load_roots()
    print(f"Loaded {len(roots)} roots.")

    out: dict[str, list[str]] = {}
    if OUT_JSON.exists():
        out = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        print(f"Resuming: {len(out)} roots already fetched.")

    if mode == "probe":
        roots = roots[:5]
        print("\n--- PROBE MODE (5 roots, verbose) ---\n")

    total = len(roots)
    for idx, letters_ar in enumerate(roots, 1):
        if letters_ar in out:
            continue
        bw = arabic_to_buckwalter(letters_ar)
        # Cache by Arabic code points, NOT Buckwalter: Buckwalter is
        # case-sensitive (sfh ≠ SfH ≠ sfH) but Windows filenames are not, and
        # it uses '*' (ذ) which Windows rejects.
        html_path = HTML_DIR / ("_".join(f"{ord(ch):04x}" for ch in letters_ar) + ".html")
        html = fetch_page(bw, html_path)
        if html is None:
            out[letters_ar] = []
            print(f"  [{idx}/{total}] {letters_ar} ({bw})  FAIL")
        else:
            toks = extract_tokens(html)
            out[letters_ar] = toks
            if mode == "probe" or idx % 50 == 0:
                top = [w for w, _ in Counter(toks).most_common(8)]
                print(f"  [{idx}/{total}] {letters_ar} ({bw})  {len(toks)} toks  top={top}")

        if idx % 25 == 0:
            OUT_JSON.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")

        if mode == "full":
            time.sleep(DELAY_SEC)

    OUT_JSON.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"\nDone. Wrote {OUT_JSON} ({len(out)} entries).")
    if mode == "probe":
        print("\nIf the top tokens above look sensible, run:")
        print("    python fetch_qac_glosses.py full")
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    if mode not in ("probe", "full"):
        print("usage: fetch_qac_glosses.py [probe|full]")
        sys.exit(2)
    sys.exit(run(mode))
