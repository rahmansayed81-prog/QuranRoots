"""
خ-65 / خ-70: review root error reports and print them with context, grouped
by word, so each can become a rule in hadith_overrides.json (hadith) or a
fix in the Quran morphology layer (ayah).

Reports arrive by e-mail at quran.roots.apps@gmail.com. Each mail carries a
block between «---QR-REPORT v1---» and «---END---». Save the mails (or
paste their text) into .txt/.eml files, or pass a Drive backup JSON:

    python review_reports.py reports/*.txt
    python review_reports.py quranroots-backup.json
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).parent
DEFAULT_DB = HERE.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"
BLOCK = re.compile(r"---QR-REPORT v1---\s*(\{.*?\})\s*---END---", re.S)


def load(paths: list[str]) -> list[dict]:
    reports: list[dict] = []
    for p in paths:
        text = Path(p).read_text(encoding="utf-8", errors="replace")
        if text.lstrip().startswith("{"):
            reports += json.loads(text).get("root_reports", [])
            continue
        for m in BLOCK.finditer(text):
            try:
                reports.append(json.loads(m.group(1)))
            except json.JSONDecodeError:
                print(f"! unreadable report block in {p}")
    return reports


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    reports = load(argv[1:])
    if not reports:
        print("No root reports found.")
        return 0
    db = sqlite3.connect(f"file:{DEFAULT_DB.as_posix()}?mode=ro", uri=True)

    def context(r: dict) -> str:
        if r["kind"] == "ayah":
            row = db.execute("SELECT text_simple FROM ayahs WHERE surah_no=? AND ayah_no=?", (r["s"], r["a"])).fetchone()
            return f"{r['s']}:{r['a']}  {row[0] if row else ''}"
        row = db.execute("SELECT book, hadith_no, tokens FROM hadith WHERE hid=?", (r["hid"],)).fetchone()
        if not row:
            return f"hid {r['hid']} (not in this DB)"
        toks = row[2].split(" ")
        i = r["word_no"] - 1
        return f"{row[0]} {row[1]}  …{' '.join(toks[max(0, i - 4):i + 5])}…"

    # The same report may arrive twice (resent); keep one per position.
    seen, unique = set(), []
    for r in reports:
        key = (r["kind"], r.get("s"), r.get("a"), r.get("hid"), r["word_no"], r.get("suggested_root"))
        if key not in seen:
            seen.add(key)
            unique.append(r)

    by_word: defaultdict[str, list] = defaultdict(list)
    for r in unique:
        by_word[r["word"]].append(r)
    print(f"{len(unique)} reports ({len(reports) - len(unique)} duplicates dropped), {len(by_word)} distinct words\n")
    for word, rs in sorted(by_word.items(), key=lambda kv: -len(kv[1])):
        sugg = {r.get("suggested_root") or "?" for r in rs}
        cur = {r.get("current_root") or "—" for r in rs}
        print(f"■ {word}   {', '.join(sorted(cur))} → {', '.join(sorted(sugg))}   ({len(rs)}×)")
        for r in rs:
            note = f"   # {r['note']}" if r.get("note") else ""
            ver = f"   [app {r.get('app')}, db {r.get('db')}]" if r.get("app") else ""
            print(f"    [{r['kind']}] {context(r)}{note}{ver}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
