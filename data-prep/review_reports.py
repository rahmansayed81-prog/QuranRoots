"""
خ-65: read the user's root error reports from a Drive backup
(quranroots-backup.json) and print them with context, grouped by word,
so each can become a rule in hadith_overrides.json (hadith) or a fix in
the Quran morphology layer (ayah).

    python review_reports.py path/to/quranroots-backup.json [path/to/quran-roots.db]
"""

from __future__ import annotations

import json
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).parent
DEFAULT_DB = HERE.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    backup = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    reports = backup.get("root_reports", [])
    if not reports:
        print("No root reports in this backup.")
        return 0
    db = sqlite3.connect(f"file:{Path(argv[2] if len(argv) > 2 else DEFAULT_DB).as_posix()}?mode=ro", uri=True)

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

    by_word: defaultdict[str, list] = defaultdict(list)
    for r in reports:
        by_word[r["word"]].append(r)
    print(f"{len(reports)} reports, {len(by_word)} distinct words\n")
    for word, rs in sorted(by_word.items(), key=lambda kv: -len(kv[1])):
        sugg = {r.get("suggested_root") or "?" for r in rs}
        cur = {r.get("current_root") or "—" for r in rs}
        print(f"■ {word}   {', '.join(sorted(cur))} → {', '.join(sorted(sugg))}   ({len(rs)}×)")
        for r in rs:
            note = f"   # {r['note']}" if r.get("note") else ""
            print(f"    [{r['kind']}] {context(r)}{note}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
