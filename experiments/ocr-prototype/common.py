"""Shared helpers: paths, page sets, text normalisation, error rates, key scoring."""
import json, os, re
from rapidfuzz.distance import Levenshtein
from rapidfuzz import fuzz

ROOT = os.environ.get("OCR_ROOT", os.path.expanduser("~/ocr-experiment"))
IN, WORK, OUT = (os.path.join(ROOT, d) for d in ("in", "work", "out"))
OUT = os.environ.get("OCR_OUT", OUT)  # scoring runs on another machine over a copy of out/
HERE = os.path.dirname(os.path.abspath(__file__))
PDF = {"A": os.path.join(IN, "wells.pdf"), "B": os.path.join(IN, "lesson.pdf"), "C": os.path.join(WORK, "lesson_photocopy.pdf")}
B_PAGES = [1 + 3 * i for i in range(20)]  # 20 pages spread over the 62-page lesson
C_PAGES = list(range(1, 21))  # page i of C is page B_PAGES[i-1] of B

def key():
    return [q for q in json.load(open(os.path.join(HERE, "key_wells.json"), encoding="utf-8")) if q["id"].startswith("Q")]

def qpages(q):
    return q["page"] if isinstance(q["page"], list) else [q["page"]]

A_ALL = sorted({p for q in key() for p in qpages(q)})  # 25 pages the 27 questions point at
TABLE_PAGES = [81, 104, 108, 138]  # Q17-Q20
A20 = [p for p in A_ALL if p in TABLE_PAGES] + [p for p in A_ALL if p not in TABLE_PAGES][::-1][:16]
A20 = sorted(A20)  # all 4 table pages + 16 other key pages; the 5 others are scored for answers only
PAGES = {"A": A_ALL, "B": B_PAGES, "C": C_PAGES}

def norm(s):
    return re.sub(r"\s+", " ", s).strip()

def cer(ref, hyp):
    ref, hyp = norm(ref), norm(hyp)
    return Levenshtein.distance(ref, hyp) / max(1, len(ref))

def wer(ref, hyp):
    r, h = norm(ref).split(), norm(hyp).split()
    return Levenshtein.distance(r, h) / max(1, len(r))

def bag_f1(ref, hyp):
    """Order-insensitive word overlap, so column/table reading order does not hide good recognition."""
    from collections import Counter
    r, h = Counter(norm(ref).lower().split()), Counter(norm(hyp).lower().split())
    hit = sum((r & h).values())
    p, rc = hit / max(1, sum(h.values())), hit / max(1, sum(r.values()))
    return 2 * p * rc / max(1e-9, p + rc)

def tokens_of(q):
    t = q.get("token", q["expected_answer"])
    return t if isinstance(t, list) else [t]

def quotes_of(q):
    t = q["quote"]
    return t if isinstance(t, list) else [t]

def score_question(q, page_texts):
    """page_texts: {page:int -> text}. Returns (tokens_present, quote_present) on the key's own page(s)."""
    txt = norm(" ".join(page_texts.get(p, "") for p in qpages(q))).lower()
    tok = all(norm(t).lower() in txt for t in tokens_of(q))
    quo = all(fuzz.partial_ratio(norm(s).lower(), txt) >= 90 for s in quotes_of(q))
    return tok, quo
