"""خ-58 / خ-61: هل الجذور التي تشترك في حرفين أقرب معنًى من الصدفة؟
وأيّ حرفين: الأولان (ف ع)، أم الآخران (ع ل)، أم الطرفان (ف ل)؟

الحَكَم: تعريفات عربية — مقاييس اللغة (جملة «يدل على…» فقط) والمفردات
للراغب (صدر المادة). لمنع الانحياز الحرفي تُحذف من نصَّي كل زوج كلُّ كلمة
مشتقة من أيٍّ من الجذرين، وتُحذف أسماء الحروف التي يفتتح بها ابن فارس.

Run with a Python that has pandas+pyarrow:
    python -B -I research/binary_test_v2.py
Reads cache/lexicons/*.parquet (git-ignored) and the app DB (read-only).
"""
import itertools, math, random, re, sqlite3, sys
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path

import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent.parent
LEX = HERE / "cache" / "lexicons"
DB = HERE.parent / "app" / "src" / "main" / "assets" / "quran-roots.db"

DIAC = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
def norm(s):
    s = DIAC.sub("", s)
    s = re.sub("[أإآٱءؤئ]", "ا", s)
    return s.replace("ى", "ي").replace("ة", "ه")

_NAMES = ("ألف همزة باء تاء ثاء جيم حاء خاء دال ذال راء زاء زاي سين شين صاد ضاد طاء "
          "ظاء عين غين فاء قاف كاف لام ميم نون هاء واو ياء").split()
LETTER_NAMES = {norm(p + x) for x in _NAMES for p in ("", "ال", "وال")}
STOP = {norm(x) for x in (
    "من في على عن الى إلى ما لا ان أن إن او أو ثم قد كان هو هي ذلك هذا هذه التي الذي "
    "وهو وهي به بها له لها منه منها فيه فيها عليه كل بعض شيء الشيء شيئ اصل أصل اصلان "
    "أصلان صحيح يدل تدل يقال قال قالوا الباب واحد احدهما أحدهما والآخر الاخر الآخر "
    "والاخر معنى ومن وهذا وذلك كأنه كانه اذا إذا وقد لم ولا بين غير مثل نحو اي أي "
    "أصول اصول الكلمة كلمة كلمات أما اما أول اول الأول ثاني الثاني ثالث الثالث أخرى اخرى "
    "الشا شا شيا الشيا تعالى تعالي قيل قوله الله عز وجل كذا لما الا وما وان كما يكون فهو انه غيره تقول "
    "ثلاثة ثلاث اصلين أصلين أحدها الآخران متقاربان متباينان يقاس عليه منه بابه".split())}
PREFIXES = ("وال", "فال", "بال", "كال", "لل", "ال", "و", "ف", "ب", "ل", "ك")

def tokens(text):
    out = []
    for w in re.findall("[ء-ي]+", norm(text)):
        if w in STOP or w in LETTER_NAMES or len(w) < 2:
            continue
        for p in PREFIXES:
            if w.startswith(p) and len(w) - len(p) >= 3:
                w = w[len(p):]
                break
        if w not in STOP and w not in LETTER_NAMES:
            out.append(w)
    return out

WEAK = set("اوي")
@lru_cache(maxsize=None)
def derived(tok, root):
    """tok contains the root's letters in order (weak radicals optional) —
    a derivative of that root, whose presence would leak shared letters."""
    i = 0
    for ch in root:
        j = tok.find(ch, i)
        if j < 0:
            if ch in WEAK:
                continue
            return False
        i = j + 1
    return True

def ibn_faris(text):
    # drop the opening «القاف والطاء والعين أصل…»: everything up to the first
    # يدل/تدل (or, failing that, أصل/أصول) — letter names leak shared letters.
    t = DIAC.sub("", text)
    m = re.search("(?:يدل|تدل)", t[:250]) or re.search("(?:أصلان|أصول|أصل|اصل)", t[:250])
    if m:
        t = t[m.end():]
    t = re.split(r"(?:من ذلك|فالأول|فالاول|يقال|ومن الباب|قال ال)", t, maxsplit=1)[0]
    return t[:300]

def raghib(text, root):
    t = re.sub(r"\[[^\]]*\]", " ", text)       # [سورة/آية]
    return t[:350]

def load():
    q = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    qroots = {norm(l.replace(" ", "")): l.replace(" ", "") for (l,) in q.execute(
        "SELECT letters FROM roots WHERE root_id IN (SELECT DISTINCT root_id FROM words WHERE root_id IS NOT NULL)")}
    qroots = {k: v for k, v in qroots.items() if len(k) == 3}
    a = pd.read_parquet(LEX / "islamlab_arabic_lexicons.parquet", columns=["title_ar", "root", "text"])
    a = a[a.title_ar == "معجم مقاييس اللغة"]
    b = pd.read_parquet(LEX / "rashad_arabic_roots.parquet", columns=["book_name", "root", "definition"])
    b = b[b.book_name.str.contains("المفردات")]
    src = {"مقاييس اللغة": defaultdict(str), "المفردات": defaultdict(str)}
    for r, t in zip(a.root, a.text):
        k = norm(str(r))
        if k in qroots:
            src["مقاييس اللغة"][k] += " " + ibn_faris(str(t))
    for r, t in zip(b.root, b.definition):
        k = norm(str(r))
        if k in qroots:
            src["المفردات"][k] += " " + raghib(str(t), k)
    return src

def vectors(texts):
    docs = {r: Counter(tokens(t)) for r, t in texts.items()}
    docs = {r: d for r, d in docs.items() if d}
    n = len(docs)
    df = Counter(w for d in docs.values() for w in d)
    return {r: {w: (1 + math.log(c)) * math.log(n / df[w]) for w, c in d.items() if df[w] < n}
            for r, d in docs.items()}

STRICT = "--strict" in sys.argv

@lru_cache(maxsize=None)
def shared(a, b):
    """letters a and b share, in a's order — the pair's common ثنائي (if ≥2)."""
    rest = list(b); out = ""
    for ch in a:
        if ch in rest:
            rest.remove(ch); out += ch
    return out if len(out) >= 2 else ""

def leak(w, a, b):
    if derived(w, a) or derived(w, b):
        return True
    # --strict: also drop words of any third root carrying the shared ثنائي
    # (قطف/قطر both glossed with «قطع»), applied to every pair alike.
    s = STRICT and shared(a, b)
    return bool(s) and derived(w, s)

def make_cos(vec):
    def cos(a, b):
        va = {w: x for w, x in vec[a].items() if not leak(w, a, b)}
        vb = {w: x for w, x in vec[b].items() if not leak(w, a, b)}
        na = math.sqrt(sum(x * x for x in va.values()))
        nb = math.sqrt(sum(x * x for x in vb.values()))
        if not na or not nb:
            return 0.0
        return sum(x * vb.get(w, 0) for w, x in va.items()) / (na * nb)
    return cos

KEYS = {
    "الطرفان (ف…ل)": lambda r: r[0] + r[2],
    "الأولان (ف ع)": lambda r: r[0] + r[1],
    "الآخران (ع ل)": lambda r: r[1] + r[2],
}

def test(name, vec, roots, exclude=None, shuffles=300, seed=7):
    rnd = random.Random(seed)
    roots = [r for r in roots if not (exclude and exclude(r))]
    cos = make_cos(vec)
    base = [cos(*rnd.sample(roots, 2)) for _ in range(30000)]
    mb = sum(base) / len(base)
    print(f"\n  [{name}] جذور={len(roots):,}  خط الأساس (عشوائي)={mb:.4f}")
    rows = []
    for kname, k in KEYS.items():
        g = defaultdict(list)
        for r in roots:
            g[k(r)].append(r)
        groups = [v for v in g.values() if len(v) > 1]
        sims = [cos(a, b) for v in groups for a, b in itertools.combinations(v, 2)]
        m = sum(sims) / len(sims)
        sizes = [len(v) for v in g.values()]
        null = []
        for _ in range(shuffles):
            sh = roots[:]; rnd.shuffle(sh); i = 0; s = []
            for n in sizes:
                grp = sh[i:i + n]; i += n
                s += [cos(a, b) for a, b in itertools.combinations(grp, 2)]
            null.append(sum(s) / len(s))
        p = max(sum(x >= m for x in null), 1) / shuffles
        rows.append((kname, len(sims), m, m / mb, p))
        print(f"    {kname}: أزواج={len(sims):,}  متوسط={m:.4f}  ×{m / mb:.2f}  p≤{p:.3f}")
    # تقاليب
    g = defaultdict(list)
    for r in roots:
        g["".join(sorted(r))].append(r)
    sims = [cos(a, b) for v in g.values() for a, b in itertools.combinations(v, 2) if a != b]
    if sims:
        m = sum(sims) / len(sims)
        print(f"    التقاليب: أزواج={len(sims):,}  متوسط={m:.4f}  ×{m / mb:.2f}")
    return rows

def main():
    src = load()
    doubled = lambda r: r[1] == r[2] or r[0] == r[1] or r[0] == r[2]
    weak = lambda r: any(c in "اوي" for c in r)
    for sname, texts in src.items():
        vec = vectors(texts)
        print(f"\n=== الحَكَم: {sname} — {len(vec):,} جذرًا قرآنيًا ===")
        roots = sorted(vec)
        test("الكل" + (" — صارم" if STRICT else ""), vec, roots)
        if not STRICT:
            test("بلا مضعّف", vec, roots, exclude=doubled)
            test("بلا مضعّف ولا معتل/مهموز", vec, roots, exclude=lambda r: doubled(r) or weak(r))

if __name__ == "__main__":
    main()
