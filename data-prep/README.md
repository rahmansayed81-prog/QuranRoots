# Data preparation

Builds the offline SQLite database (`app/src/main/assets/quran-roots.db`) that the Android app loads on first launch.

## Sources used

| File | Source | License |
|------|--------|---------|
| `quran-uthmani.txt` | [Tanzil](https://tanzil.net/download/) | Tanzil terms (free, non-commercial) |
| `quran-simple.txt`  | [Tanzil](https://tanzil.net/download/) | Tanzil terms |
| `quranic-corpus-morphology-0.4.txt` | [Quranic Arabic Corpus](https://corpus.quran.com/download/) | GNU GPL |

## Run

```bash
cd data-prep
python build_db.py
```

The script downloads the three source files into `./cache/` on first run. If a download fails (some mirrors block scripted access), drop the file manually into `./cache/` with the exact filename above and re-run.

## Output

A single SQLite file at `../app/src/main/assets/quran-roots.db`. The Android build picks it up automatically and Room copies it to the app's database directory on first launch.
