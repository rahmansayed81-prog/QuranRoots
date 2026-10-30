"""
التصريف والميزان الصرفي لكل كلمة في القرآن (م1 من خ-40).

Input:  Quranic Arabic Corpus morphology (segments + features per word).
Output: table word_morph — per Quran word:
    tasreef      Arabic label from QAC features (فعل ماضٍ — مزيد: أَفْعَلَ — جمع …)
    wazn         الميزان الصرفي of the word as it stands (stem + subject affixes),
                 e.g. عَامِلُونَ → فَاعِلُونَ, عَمِلُوا → فَعِلُوا, قَالَ → فَعَلَ
    lemma_wazn   الميزان of the lemma (عَامِل → فَاعِل)
    status       ok      every radical found as a sound consonant
                 weak    found, after a weak/hamza/doubling rule
                 review  radicals could not be aligned — needs a human or the committee
                 noroot  QAC gives no root (particles, pronouns, proper nouns …)

How the ميزان is made: the radicals of the root are aligned to the letters of
the word; each radical becomes ف / ع / ل (quadriliteral: ف ع ل ل) and every
other letter and vowel is kept. Classical conventions applied:
  * a weak radical that became a long vowel is weighed by its letter with the
    original vowel moved back (يَقُولُ → يَفْعُلُ، قَالَ → فَعَلَ، مَقَام → مَفْعَل);
  * a deleted radical is dropped from the ميزان (قُلْ → فُلْ، عِدْ → عِلْ);
  * a doubled root written with shadda is weighed in full (رَبّ → فَعْل، مَدَّ → فَعَلَ);
  * form VIII: the infix ت is weighed as ت even when assimilated
    (اصْطَبَرَ / اتَّقَى / ادَّكَرَ → افْتَعَلَ);
  * the shadda of ال on a sun letter is not part of the word.
"""

from __future__ import annotations

import re
from collections import Counter, defaultdict
from itertools import combinations

# --------------------------------------------------------------------------
# Buckwalter helpers (QAC uses extended Buckwalter)
# --------------------------------------------------------------------------

VOWELS = set("aiuoFNK~")           # harakat, sukun, tanween, shadda
LONG_A = set("A`{Y")               # letters that are a long ā (or alif seat)
HAMZA = set("'><&}|")
QURANIC_MARKS = set("^@:;,.[]-+%\"0123456789!_=*")  # '*' is ذ — handled below
SUN = set("tvd*rzs$SDTZln")
# حروف الزيادة «سألتمونيها» + alif forms + ة; anything else must be a radical.
ZAWAID = set("s'><&}|ltmwnyhAY`{p")

RADICAL_SYMBOLS = {3: ["ف", "ع", "ل"], 4: ["ف", "ع", "ل", "ل"], 5: ["ف", "ع", "ل", "ل", "ل"]}

BW2AR = {
    "'": "ء", "|": "آ", ">": "أ", "&": "ؤ", "<": "إ", "}": "ئ", "A": "ا", "b": "ب",
    "p": "ة", "t": "ت", "v": "ث", "j": "ج", "H": "ح", "x": "خ", "d": "د", "*": "ذ",
    "r": "ر", "z": "ز", "s": "س", "$": "ش", "S": "ص", "D": "ض", "T": "ط", "Z": "ظ",
    "E": "ع", "g": "غ", "_": "ـ", "f": "ف", "q": "ق", "k": "ك", "l": "ل", "m": "م",
    "n": "ن", "h": "ه", "w": "و", "Y": "ى", "y": "ي", "F": "ً", "N": "ٌ", "K": "ٍ",
    "a": "َ", "u": "ُ", "i": "ِ", "~": "ّ", "o": "ْ", "`": "ٰ", "{": "ا",
}


def tokenize(bw: str) -> list[list[str]]:
    """Split a Buckwalter word into [letter, diacritics…] tokens, dropping
    Quranic annotation marks. '*' is the letter ذ in Buckwalter."""
    toks: list[list[str]] = []
    for ch in bw:
        if ch == "#":                        # small high hamza (ـَٔا)
            ch = "'"
        if ch in QURANIC_MARKS and ch != "*":
            continue
        if ch in VOWELS:
            if toks:
                toks[-1].append(ch)
            continue
        if ch == "`" and toks and toks[-1][0] in "wyY" and len(toks[-1]) == 1:
            # ىٰ / وٰ: the dagger sits on a seat letter — the seat is the long ā
            toks[-1].append("`")
            continue
        toks.append([ch])
    return toks


def render(tokens: list[list[str]]) -> str:
    out = []
    for t in tokens:
        out.append(t[0] if len(t[0]) > 1 else BW2AR.get(t[0], t[0]))
        out.extend(BW2AR.get(d, d) for d in t[1:])
    return "".join(out)


_AR_DIAC = re.compile("[ً-ْٰـ]")


def plain(ar: str) -> str:
    """Search key: no diacritics, alif forms folded."""
    s = _AR_DIAC.sub("", ar)
    return s.replace("ٱ", "ا").replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")


# --------------------------------------------------------------------------
# Alignment of radicals to letters
# --------------------------------------------------------------------------

root_hint = ""


def can_realize(letter: str, radical: str, vf: int, first: bool = False, shadda: bool = False) -> int:
    """0 = no; 3 = same consonant; 2 = hamza/weak consonant variant; 1 = long vowel
    (or, in form VIII, ف assimilated into the infix: اتَّقَى، ادَّكَرَ، اطَّهَّرُوا)."""
    if vf == 8 and first and shadda and letter in "tdT" and letter != radical:
        return 1
    if radical == "A":                       # QAC writes a hamza radical as A
        if letter in HAMZA:
            return 3
        if letter in LONG_A:
            return 1
        if letter in "wy":                   # تخفيف الهمز (يُؤْمِنُ written with w seat etc.)
            return 2
        return 0
    if letter == radical:
        return 3
    if radical == "h" and letter in HAMZA and root_hint == "mwh":   # مَاء أصلها مَوَه
        return 2
    if radical in "wy":
        if letter in "wy":
            return 2
        if letter in HAMZA:                  # قائم، سماء
            return 2
        if letter in LONG_A:
            return 1
        return 0
    return 0


def align(toks: list[list[str]], root: str, vf: int, limit: int | None = None):
    """Choose letter positions for the radicals. Returns list[int|None]
    (None = radical deleted or carried by a shadda) or None if impossible."""
    n, k = len(toks), len(root)
    letters = [t[0] for t in toks]
    stop = n if limit is None else limit
    best = None

    def score_of(pos):
        sc = 0
        for i, p in enumerate(pos):
            if p is None:
                sc -= 2
            elif p == "~":
                sc += 1
            else:
                sc += can_realize(letters[p], root[i], vf, i == 0, "~" in toks[p])
        # radicals sit close together: prefer compact alignments
        real = [p for p in pos if isinstance(p, int)]
        if real:
            sc -= 0.01 * (real[-1] - real[0])
        return sc

    def rec(i, start, pos):
        nonlocal best
        if i == k:
            used = {p for p in pos if isinstance(p, int)}
            infix = pos[0] + 1 if vf == 8 and isinstance(pos[0], int) else -1
            for j, l in enumerate(letters):          # every non-radical letter must be a زائدة
                if j not in used and l not in ZAWAID and not (j == infix and l in "Tdz*"):
                    return
            if all(p is None or p == "~" for p in pos):
                return
            sc = score_of(pos)
            if best is None or sc > best[0]:
                best = (sc, list(pos))
            return
        r = root[i]
        for j in range(start, stop):
            if can_realize(letters[j], r, vf, i == 0, "~" in toks[j]):
                rec(i + 1, j + 1, pos + [j])
        # doubled radical carried by the previous radical's shadda
        if i > 0 and r == root[i - 1] and isinstance(pos[-1], int) and "~" in toks[pos[-1]]:
            rec(i + 1, start, pos + ["~"])
        # a weak or hamza radical may be deleted; so may the repeat of a
        # doubled root (ظَلْتَ) and the ن of كان (يَكُ)
        if r in "wyA" or (i > 0 and r == root[i - 1]) or (root == "kwn" and r == "n"):
            rec(i + 1, start, pos + [None])

    rec(0, 0, [])
    return best[1] if best else None


# --------------------------------------------------------------------------
# The ميزان
# --------------------------------------------------------------------------

def vowel_of(tok):
    for d in tok[1:]:
        if d in "aiuoFNK":
            return d
    return None


def set_vowel(tok, v):
    keep = [d for d in tok[1:] if d not in "aiuoFNK"]
    return [tok[0]] + keep + ([v] if v else [])


def weigh(bw: str, root: str, *, vf: int = 1, pos_tag: str = "N", aspect: str | None = None,
          det_sun: bool = False, suffix_bw: str = "", plural_ending: str | None = None):
    """Return (wazn_ar, status) for one stem (Buckwalter) plus its subject suffix."""
    toks = tokenize(bw + suffix_bw)
    n_suffix = len(tokenize(suffix_bw)) if suffix_bw else 0
    # كُنَّا / آمَنَّا: the ن of the root merged into the ن of «نا»
    if n_suffix and root[-1] == "n" and toks[-n_suffix][0] == "n" and "~" in toks[-n_suffix]             and toks[-n_suffix - 1][0] != "n":
        k = len(toks) - n_suffix
        toks[k] = [x for x in toks[k] if x != "~"]
        toks.insert(k, ["n", "o"])
    if not toks:
        return None, "review"
    if "~" in toks[0]:
        toks[0] = [x for x in toks[0] if x != "~"]
    # ٱلَّيْل: the ل of the word is written once with the ل of ال
    if det_sun and root[0] == "l" and toks[0][0] != "l":
        toks.insert(0, ["l", "a" if root[1:2] != "l" else "o"])
    # آمَنَ (form IV of a hamza-initial root): ءَا = أَ + ءْ
    if vf == 4 and root[0] == "A" and toks[0][0] == "'" and len(toks) > 1 and toks[1] == ["A"]:
        toks[0:2] = [[">", "a"], ["'", "o"]]
    # شَىْءٍ: a dotless ى carrying a vowel or sukun is the consonant ي
    for t in toks:
        if t[0] == "Y" and any(d in "aiuoFNK" for d in t[1:]):
            t[0] = "y"
    # form VIII with assimilated or emphatic infix: the letter after ف that
    # stands for the ت of افتعل (اصطبر، ازدجر، ادّكر) is weighed as ت.
    # CAMeL-style spelling leaves the fatha before ā unwritten (شَراب): restore it
    for j in range(len(toks) - 1):
        if toks[j + 1] == ["A"] and not any(d in "aiuoFNK" for d in toks[j][1:]) and toks[j][0] not in "A{":
            toks[j].append("a")
    global root_hint
    root_hint = root
    limit = len(toks) - n_suffix
    if plural_ending and root[-1] in "wyA":
        letters = "".join(t[0] for t in toks[:limit])
        m = re.search(r"(wn|yn|w|y|At|`t|A`t|t)$", letters) if plural_ending == "MP" else \
            re.search(r"(At|`t|t)$", letters)
        if m:
            limit -= len(m.group(1))
    pos = align(toks, root, vf, limit)
    if pos is None:
        return None, "review"
    syms = RADICAL_SYMBOLS.get(len(root))
    if syms is None:
        return None, "review"

    out = [list(t) for t in toks]
    status = "ok"
    seat_radical = {p for p in pos if isinstance(p, int) and "`" in toks[p][1:]}
    radical_at = {p: i for i, p in enumerate(pos) if isinstance(p, int)}
    deleted = [i for i, p in enumerate(pos) if p is None]
    if deleted:
        status = "weak"

    for j, t in enumerate(toks):
        if j not in radical_at:
            continue
        i = radical_at[j]
        sym = syms[i]
        letter = t[0]
        strength = can_realize(letter, root[i], vf, i == 0, "~" in t)
        pv = vowel_of(out[j - 1]) if j > 0 else None
        nv = [d for d in t[1:] if d in "aiuFNK"]
        if letter == "|" and root[i] == "A":
            # آ = hamza + ā (آذِن → فَاعِل)
            out[j] = [sym, "a", "A"]
            continue
        if letter in "wy" and root[i] == letter and "~" in t and i == len(root) - 1 and pv in ("u", "i"):
            # عَدُوّ / غَنِيّ: a long vowel merged with the weak ل → فَعُول / فَعِيل
            out[j] = [BW2AR[letter] + sym] + nv
            status = "weak"
            continue
        if letter in "wy" and root[i] in "wy" and "~" in t and i == 1 and pv == "a" and pos_tag != "V" \
                and nv[:1] in (["i"], ["u"]):
            # سَيِّد / طَيِّب / قَيُّوم: ي زائدة merged with the weak ع → فَيْعِل / فَيْعُول
            out[j] = ["يْ" + sym] + nv
            status = "weak"
            continue
        if strength == 3:
            out[j] = [sym] + t[1:]
        elif strength == 2:
            out[j] = [sym] + t[1:]
            status = "weak"
        else:                                         # long vowel standing for the radical
            status = "weak"
            prev = j - 1
            if letter == "Y" and prev >= 0 and vowel_of(out[prev]) == "i":
                # أَدْرِى / يَهْدِى: final ى after kasra is ي — original vowel restored
                out[j] = [sym] + (["u"] if pos_tag == "V" and aspect == "IMPF" else [])
            elif letter in LONG_A:
                v = vowel_of(t) if vowel_of(t) in ("F", "N", "K") else "a"
                out[j] = [sym, v]
                # مَقَام / يَخَاف / أَقَام: a prefixed ف has no vowel of its own
                if i == 1 and prev >= 0 and prev in radical_at and radical_at[prev] == 0 and prev > 0:
                    out[prev] = set_vowel(out[prev], "o")
                if prev >= 0 and vowel_of(out[prev]) in ("F", "N", "K"):   # هُدًى
                    out[j] = [sym, vowel_of(out[prev])]
                    out[prev] = set_vowel(out[prev], "a")
            elif letter in "tdT":                     # VIII: ف assimilated into the infix
                out[j] = [sym, "o", "t"] + [d for d in t[1:] if d != "~"]
            else:
                out[j] = [sym] + t[1:]

    # long ū / ī written as و / ي for a weak ع: نقل الحركة when ف follows a
    # prefix (يَقُولُ → يَفْعُلُ، يَبِيعُ → يَفْعِلُ); otherwise the radical is
    # weighed with sukun (دُون → فُعْل).
    for j, t in enumerate(toks):
        if j in radical_at and radical_at[j] == 1 and t[0] in "wy" and root[1] in "wy" and len(t) == 1:
            prev = j - 1
            pv = vowel_of(out[prev]) if prev >= 0 else None
            if (t[0] == "w" and pv == "u") or (t[0] == "y" and pv == "i"):
                if prev in radical_at and prev > 0:
                    out[j] = [syms[1], pv]
                    out[prev] = set_vowel(out[prev], "o")
                else:
                    out[j] = [syms[1], "o"]

    # doubled root: the shadda carries the next radical (رَبّ → فَعْل)
    for i, p in enumerate(pos):
        if p == "~":
            status = "weak"
            j = pos[i - 1]
            src = out[j]
            after = [d for d in src[1:] if d != "~"]
            v_before = vowel_of(src) if pos_tag == "V" and aspect == "PERF" else "o"
            out[j] = [src[0], "a" if pos_tag == "V" and aspect == "PERF" else "o"]
            tail = [x for x in after if x in "aiuoFNK"]
            out.insert(j + 1, [syms[i]] + (tail or []))
            # يَمُدُّ → يَفْعُلُ: the vowel of ف moves to ع
            if pos_tag == "V" and aspect in ("IMPF", "IMPV") and i == 2 and isinstance(pos[0], int):
                f = pos[0]
                fv = vowel_of(out[f])
                if fv and fv != "o":
                    out[f] = set_vowel(out[f], "o")
                    out[j] = [out[j][0], fv]
            break   # only one doubling per word

    # Form VIII: the infix (ت/ط/د/ذ/ز) right after ف is weighed as ت
    if vf == 8 and isinstance(pos[0], int):
        f = pos[0]
        nxt = f + 1
        if nxt < len(out) and out[nxt][0] in ("T", "d", "*", "z") and nxt not in radical_at:
            out[nxt] = ["t"] + out[nxt][1:]
        # ادَّكَرَ / اطَّهَّرُوا: ف assimilated into the infix — restore افْتَ
        if out[f][0] == "ف" and "~" in out[f]:
            pass

    # a non-radical dagger alif is weighed as ا (كِتَٰب → فِعَال)
    j = 0
    while j < len(out):
        t = out[j]
        if t[0] == "`":
            out[j] = ["A"] + t[1:]
        elif "`" in t[1:]:
            out[j] = [x for x in t if x != "`"]
            if j in seat_radical:                    # صَلَوٰة: the seat is the radical, read ā
                out[j] = set_vowel(out[j], "a") if not vowel_of(out[j]) else out[j]
        j += 1

    symset = set(syms)
    for j in range(len(out) - 1):
        if out[j][0] in symset and not vowel_of(out[j]):
            out[j] = set_vowel(out[j], "o")

    return render(out), status


# --------------------------------------------------------------------------
# التصريف label
# --------------------------------------------------------------------------

FORM_LABEL = {
    1: "ثلاثي مجرد", 2: "مزيد: فَعَّلَ", 3: "مزيد: فَاعَلَ", 4: "مزيد: أَفْعَلَ",
    5: "مزيد: تَفَعَّلَ", 6: "مزيد: تَفَاعَلَ", 7: "مزيد: انْفَعَلَ", 8: "مزيد: افْتَعَلَ",
    9: "مزيد: افْعَلَّ", 10: "مزيد: اسْتَفْعَلَ", 11: "مزيد: افْعَالَّ", 12: "مزيد: افْعَوْعَلَ",
}
ROMAN = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8,
         "IX": 9, "X": 10, "XI": 11, "XII": 12}
ASPECT = {"PERF": "فعل ماضٍ", "IMPF": "فعل مضارع", "IMPV": "فعل أمر"}
MOOD = {"SUBJ": "منصوب", "JUS": "مجزوم"}
PGN = re.compile(r"^([123])?([MF])?([SDP])$")

# QAC part-of-speech tags of words outside the derivational system
PARTICLE_LABEL = {
    "P": "حرف جر", "CONJ": "حرف عطف", "SUB": "حرف مصدري", "ACC": "حرف ناسخ",
    "NEG": "حرف نفي", "PRO": "حرف نهي", "CERT": "حرف تحقيق", "RES": "أداة حصر",
    "EXP": "أداة استثناء", "COND": "أداة شرط", "INTG": "أداة استفهام", "VOC": "حرف نداء",
    "AMD": "حرف استدراك", "ANS": "حرف جواب", "AVR": "حرف ردع", "CAUS": "حرف سببية",
    "CIRC": "حرف حال", "COM": "واو المعية", "EMPH": "لام التوكيد", "EQ": "حرف تسوية",
    "EXH": "حرف تحضيض", "EXL": "حرف تفصيل", "FUT": "حرف استقبال", "INC": "حرف ابتداء",
    "INT": "حرف تفسير", "PREV": "حرف كاف", "PRP": "لام التعليل", "REM": "حرف استئناف",
    "RET": "حرف إضراب", "RSLT": "حرف واقع في جواب الشرط", "SUP": "حرف زائد",
    "SUR": "حرف فجاءة", "IMPN": "لام الأمر", "INL": "حروف مقطعة",
    "PRON": "ضمير", "DEM": "اسم إشارة", "REL": "اسم موصول", "T": "ظرف زمان", "LOC": "ظرف مكان",
}


def verb_form(feats) -> int:
    for k in feats:
        m = re.fullmatch(r"\(([IVX]+)\)", k)
        if m:
            return ROMAN.get(m.group(1), 1)
    return 1


def number_gender(feats):
    for k in feats:
        m = PGN.match(k)
        if m:
            return m.group(2), m.group(3)
    return None, None


def tasreef(feats, stem_bw: str, root_len: int) -> str:
    pos = feats.get("POS", feats.get("_TAG"))
    vf = verb_form(feats)
    form = ("رباعي مجرد" if vf == 1 else "رباعي مزيد") if root_len == 4 else FORM_LABEL.get(vf, "")
    parts = []
    if pos == "V":
        asp = next((a for a in ASPECT if a in feats), None)
        parts.append(ASPECT.get(asp, "فعل"))
        if "PASS" in feats:
            parts.append("مبني للمجهول")
        mood = feats.get("MOOD")
        if mood in MOOD and asp == "IMPF":
            parts.append(MOOD[mood])
        parts.append(form)
        return " — ".join(p for p in parts if p)
    if "PCPL" in feats:
        parts.append("اسم مفعول" if "PASS" in feats else "اسم فاعل")
    elif "VN" in feats:
        parts.append("مصدر")
    elif pos == "ADJ":
        parts.append("صفة")
    elif pos == "PN":
        parts.append("علم")
    elif pos in PARTICLE_LABEL:
        return PARTICLE_LABEL[pos]      # حرف / ضمير / … : no number or form
    else:
        parts.append("اسم")
    if "PCPL" in feats or "VN" in feats or vf > 1:
        parts.append("من " + form if form else "")
    g, n = number_gender(feats)
    tail = tokenize(stem_bw)
    tail_letters = "".join(t[0] for t in tail[-3:])
    if n == "D":
        parts.append("مثنى")
    elif n == "P":
        if g == "M" and re.search(r"(wn|yn|w|y)$", tail_letters):
            parts.append("جمع مذكر سالم")
        elif g == "F" and tail_letters.endswith(("At", "`t", "A`t")):
            parts.append("جمع مؤنث سالم")
        else:
            parts.append("جمع تكسير")
    elif g == "F":
        parts.append("مؤنث")
    return " — ".join(p for p in parts if p)


# --------------------------------------------------------------------------
# Per word
# --------------------------------------------------------------------------

ALLAH_LEMMAS = {"{ll~ah", "{ll~`h", "All~ah"}

SUBJ_SUFFIX_PERSONS = {
    "PERF": {"1S", "2MS", "2FS", "3FS", "1P", "2MP", "2FP", "3MP", "3FP", "2D", "3MD", "3FD"},
    "IMPF": {"2FS", "2MP", "2FP", "3MP", "3FP", "2D", "3MD", "3FD"},
    "IMPV": {"2FS", "2MP", "2FP", "2D"},
}


def person_of(feats):
    for k in feats:
        if PGN.match(k) and k[0] in "123":
            return k
    return None


BAB = {1: "فَعَلَ", 2: "فَعَّلَ", 3: "فَاعَلَ", 4: "أَفْعَلَ", 5: "تَفَعَّلَ", 6: "تَفَاعَلَ",
       7: "انْفَعَلَ", 8: "افْتَعَلَ", 9: "افْعَلَّ", 10: "اسْتَفْعَلَ", 11: "افْعَالَّ", 12: "افْعَوْعَلَ"}
BAB4 = {1: "فَعْلَلَ", 2: "تَفَعْلَلَ", 3: "افْعَنْلَلَ", 4: "افْعَلَلَّ"}


def verb_bab(lem, root, vf):
    """The verb's باب: forms II–X are fixed; form I takes the vowel of ع from
    a sound perfect lemma (فَعَلَ / فَعِلَ / فَعُلَ)."""
    if len(root) == 4:
        return BAB4.get(vf, "فَعْلَلَ")
    if vf != 1:
        return BAB.get(vf)
    if lem and root[1] not in "wyA" and root[1] != root[2]:
        toks = tokenize(lem)
        letters = [t[0] for t in toks]
        if len(toks) == 3 and letters[1] == root[1]:
            v = vowel_of(toks[1])
            if v in ("a", "i", "u"):
                return "فَع" + BW2AR[v] + "لَ"
    return "فَعَلَ"


def analyze_word(segs):
    """segs: [(seg_no, form_bw, feats)] sorted. Returns dict or None."""
    stem_i = next((i for i, (_, _, f) in enumerate(segs) if "STEM" in f), None)
    if stem_i is None:
        return None
    _, stem_bw, f = segs[stem_i]
    root = f.get("ROOT")
    det = any(sf.get("_TAG") == "DET" for _, _, sf in segs[:stem_i])
    if not root:
        return {"tasreef": tasreef(f, stem_bw, 3), "wazn": None, "lemma_wazn": None,
                "status": "noroot"}
    vf = verb_form(f)
    pos_tag = f.get("POS", f.get("_TAG"))
    aspect = next((a for a in ("PERF", "IMPF", "IMPV") if a in f), None)
    suffix_bw = ""
    if pos_tag == "V" and aspect:
        person = person_of(f)
        if person in SUBJ_SUFFIX_PERSONS[aspect] and stem_i + 1 < len(segs):
            _, suf_bw, sf = segs[stem_i + 1]
            if sf.get("_TAG") == "PRON" and sf.get("PRON") == person:
                suffix_bw = suf_bw
    lem = f.get("LEM")
    if lem in ALLAH_LEMMAS:       # لفظ الجلالة: not weighed mechanically — for the committee
        return {"tasreef": "علم — لفظ الجلالة", "wazn": None, "lemma_wazn": None, "status": "review"}
    g, num = number_gender(f)
    plural = ("MP" if g == "M" else "FP") if num == "P" and pos_tag != "V" else None
    wazn, status = weigh(stem_bw, root, vf=vf, pos_tag=pos_tag, aspect=aspect, det_sun=det,
                         suffix_bw=suffix_bw, plural_ending=plural)
    if wazn and pos_tag != "V":
        # a noun's case ending (فَعِيلٌ / فَعِيلِ / فَعِيلَ) is syntax, not ميزان
        wazn = re.sub("[ً-ِ]+$", "", wazn)
    lemma_wazn = None
    if pos_tag == "V":
        lemma_wazn = verb_bab(lem, root, vf)
    elif lem:
        lemma_wazn, _ = weigh(lem, root, vf=vf, pos_tag=pos_tag, plural_ending=plural)
    return {"tasreef": tasreef(f, stem_bw, len(root)), "wazn": wazn,
            "lemma_wazn": lemma_wazn, "status": status}


STATUS = {"ok": 0, "weak": 1, "review": 2, "noroot": 3}

# Labels and ميزان strings repeat a lot (≈2,000 distinct ميزان over 77k words),
# so they live in lookup tables and word_morph holds ids.
SCHEMA = """
CREATE TABLE IF NOT EXISTS morph_label (
    id INTEGER PRIMARY KEY,
    text TEXT NOT NULL UNIQUE          -- فعل مضارع — مزيد: أَفْعَلَ …
);
CREATE TABLE IF NOT EXISTS wazn (
    id INTEGER PRIMARY KEY,
    text TEXT NOT NULL,                -- diacritized ميزان: فَاعِلُونَ
    plain TEXT NOT NULL,               -- search key: فاعلون
    is_lemma INTEGER NOT NULL,         -- 1 = a lemma's ميزان (فاعل), 0 = a word's
    n_words INTEGER NOT NULL DEFAULT 0,
    n_roots INTEGER NOT NULL DEFAULT 0,
    UNIQUE (text, is_lemma)
);
CREATE INDEX IF NOT EXISTS wazn_plain ON wazn (plain);
CREATE TABLE IF NOT EXISTS word_morph (
    surah_no INTEGER NOT NULL,
    ayah_no INTEGER NOT NULL,
    word_no INTEGER NOT NULL,
    label_id INTEGER,
    wazn_id INTEGER,                   -- the word as it stands
    lemma_wazn_id INTEGER,             -- its lemma
    status INTEGER NOT NULL,           -- 0 ok | 1 weak (rule applied) | 2 review | 3 no root
    PRIMARY KEY (surah_no, ayah_no, word_no)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS word_morph_wazn ON word_morph (wazn_id);
CREATE INDEX IF NOT EXISTS word_morph_lwazn ON word_morph (lemma_wazn_id);
"""


def build_word_morph(cur, word_segments):
    """word_segments: {(s,a,w): [(seg, form_bw, feats)]} from the corpus."""
    cur.executescript(SCHEMA)
    labels: dict[str, int] = {}
    wazns: dict[tuple[str, int], int] = {}

    def label_id(t):
        if not t:
            return None
        if t not in labels:
            labels[t] = len(labels) + 1
        return labels[t]

    def wazn_id(t, is_lemma):
        if not t:
            return None
        key = (t, is_lemma)
        if key not in wazns:
            wazns[key] = len(wazns) + 1
        return wazns[key]

    rows, stats = [], Counter()
    for (s, a, w), segs in word_segments.items():
        r = analyze_word(sorted(segs, key=lambda x: x[0]))
        if r is None:
            continue
        stats[r["status"]] += 1
        rows.append((s, a, w, label_id(r["tasreef"]), wazn_id(r["wazn"], 0),
                     wazn_id(r["lemma_wazn"], 1), STATUS[r["status"]]))
    cur.executemany("INSERT INTO morph_label (id, text) VALUES (?,?)",
                    [(i, t) for t, i in labels.items()])
    cur.executemany("INSERT INTO wazn (id, text, plain, is_lemma) VALUES (?,?,?,?)",
                    [(i, t, plain(t), lem) for (t, lem), i in wazns.items()])
    cur.executemany("INSERT INTO word_morph VALUES (?,?,?,?,?,?,?)", rows)
    for col, lem in (("wazn_id", 0), ("lemma_wazn_id", 1)):
        cur.execute(f"""
            UPDATE wazn SET (n_words, n_roots) = (
                SELECT COUNT(*), COUNT(DISTINCT w.root_id) FROM word_morph m
                JOIN words w USING (surah_no, ayah_no, word_no) WHERE m.{col} = wazn.id)
            WHERE is_lemma = {lem}""")
    return stats


def export_review(cur, path):
    """Words still without a ميزان (no root, or not aligned), one row per
    distinct lemma, most frequent first — the committee's work list."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = cur.execute("""
        SELECT CASE m.status WHEN 2 THEN 'review' ELSE 'noroot' END, l.text,
               COALESCE(w.lemma, w.text) AS lem, COUNT(*) AS n,
               MIN(w.surah_no * 1000000 + w.ayah_no * 1000 + w.word_no), MIN(w.text)
        FROM word_morph m JOIN words w USING (surah_no, ayah_no, word_no)
        LEFT JOIN morph_label l ON l.id = m.label_id
        WHERE m.status IN (2, 3)
        GROUP BY m.status, lem
        ORDER BY n DESC""").fetchall()
    rows = [(st, lab, lem, n, f"{loc // 1000000}:{loc // 1000 % 1000}:{loc % 1000}", smp)
            for st, lab, lem, n, loc, smp in rows]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\t".join(["status", "tasreef", "lemma", "occurrences", "first_location", "sample"]) + "\n")
        for r in rows:
            f.write("\t".join("" if x is None else str(x) for x in r) + "\n")
    print(f"      review list: {len(rows):,} distinct lemmas → {path.name}")


# --------------------------------------------------------------------------
# Self-test: python build_wazn.py
# --------------------------------------------------------------------------

if __name__ == "__main__":
    import sys
    from pathlib import Path
    sys.stdout.reconfigure(encoding="utf-8")
    sys.path.insert(0, str(Path(__file__).parent))
    from build_db import parse_corpus

    segs = defaultdict(list)
    for s, a, w, seg, form, f in parse_corpus(Path(__file__).parent / "cache" / "quranic-corpus-morphology-0.4.txt"):
        segs[(s, a, w)].append((seg, form, f))
    stats = Counter()
    by_status = defaultdict(Counter)
    for key, sg in segs.items():
        r = analyze_word(sorted(sg, key=lambda x: x[0]))
        if not r:
            continue
        stats[r["status"]] += 1
        stem = next(x for x in sg if "STEM" in x[2])
        by_status[r["status"]][(stem[2].get("ROOT"), stem[1], r["wazn"], r["tasreef"])] += 1
    print(stats)
    for st in ("ok", "weak", "review"):
        print(f"--- {st}")
        for (root, form, wz, ts), n in by_status[st].most_common(25):
            print(n, root, form, "→", wz, "|", ts)
