"""Turns out/*.json into the report tables (markdown on stdout). Set OCR_OUT to a copy of the test machine's out/ folder (it must also hold gt_B.json).
Set WELLS_PDF to a local copy of wells.pdf: its pdftotext text is only used to build the word list for the artifact heuristics."""
import glob, json, os, re, statistics, subprocess, sys
from collections import Counter
from common import *

runs = {os.path.basename(f)[:-5]: json.load(open(f)) for f in sorted(glob.glob(os.path.join(OUT, "*.json"))) if not f.endswith("gt_B.json")}
gt = json.load(open(os.path.join(OUT, "gt_B.json")))
pct = lambda x: f"{100 * x:.1f}%"
def p95(xs):
    xs = sorted(xs); k = 0.95 * (len(xs) - 1); lo = int(k); hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)
def sel(r):  # the pages used for timing: 20 of A, all of B/C
    return [str(p) for p in (A20 if r["dataset"] == "A" else PAGES[r["dataset"]]) if str(p) in r["pages"]]
print("## Speed and memory\n")
print("| run | threads x procs | pages timed | total s (20 pages) | median s/page | p95 s/page | recognise-only median | peak RAM of one process (MB) |\n|---|---|---|---|---|---|---|---|")
for n, r in runs.items():
    if r["dataset"] == "Afull": continue
    ks = sel(r); tt = [r["pages"][k]["ocr_s"] + r["pages"][k]["raster_s"] for k in ks]; oo = [r["pages"][k]["ocr_s"] for k in ks]
    tot = f"{sum(tt):.1f}" + (f" (wall {r['wall_s']:.1f})" if r["procs"] > 1 else "")
    print(f"| {n} | {r['threads']} x {r['procs']} | {len(ks)} | {tot} | {statistics.median(tt):.2f} | {p95(tt):.2f} | {statistics.median(oo):.2f} | {r['peak_rss_mb']:.0f} |")

print("\n## Accuracy on B (born-digital, clean render) and C (photocopy-style scan); ground truth = pdftotext -layout of B\n")
print("| run | input | CER | WER | word-bag F1 (order-free) |\n|---|---|---|---|---|")
for n, r in runs.items():
    if r["dataset"] not in "BC" or "Afull" in n: continue
    d = ws = rl = wl = 0; hyps, refs = [], []
    for i, bp in enumerate(B_PAGES, 1):
        k = str(bp if r["dataset"] == "B" else i)
        ref, hyp = norm(gt[str(bp)]), norm(r["pages"][k]["text"])
        d += Levenshtein.distance(ref, hyp); rl += len(ref)
        ws += Levenshtein.distance(ref.split(), hyp.split()); wl += len(ref.split()); refs.append(ref); hyps.append(hyp)
    print(f"| {n} | {r['dataset']} | {pct(d / rl)} | {pct(ws / wl)} | {pct(bag_f1(' '.join(refs), ' '.join(hyps)))} |")

# ---- A: key questions
print("\n## A (Wells scan): the 27 in-scope questions, on the key's own page\n")
print("`tokens` = all key tokens present (as rag_eval.py's `ans`, whitespace/case-normalised); `quote` = the key's quoted sentence(s) present at fuzzy ratio >= 90.\n")
Q = key(); a_runs = {n: r for n, r in runs.items() if r["dataset"] == "A"}
print("| run | tokens present | quote present | tokens: table questions Q17-Q20 |\n|---|---|---|---|")
detail = {}
for n, r in a_runs.items():
    texts = {int(p): v["text"] for p, v in r["pages"].items()}
    sc = {q["id"]: score_question(q, texts) for q in Q}; detail[n] = sc
    tq = [sc[i][0] for i in ("Q17", "Q18", "Q19", "Q20")]
    print(f"| {n} | {sum(v[0] for v in sc.values())}/27 = {pct(sum(v[0] for v in sc.values()) / 27)} | {sum(v[1] for v in sc.values())}/27 = {pct(sum(v[1] for v in sc.values()) / 27)} | {sum(tq)}/4 |")
print("\nQuestions whose tokens are NOT found:\n")
for n, sc in detail.items():
    print(f"- {n}: {', '.join(i for i, v in sc.items() if not v[0]) or 'none'}")

# ---- artifacts (heuristics)
wells = os.environ.get("WELLS_PDF", PDF["A"])
book = subprocess.run(["pdftotext", wells, "-"], capture_output=True, text=True).stdout
freq = Counter(re.findall(r"[a-z]{3,}", book.lower())); vocab = {w for w, c in freq.items() if c >= 3}
LIG = "ﬁﬂﬀﬃﬄﬅﬆ"
print("\n## Artifacts on the 25 A pages (heuristics, see Not verified)\n")
print("vocab = lowercase words seen >= 3 times in pdftotext of the whole book (so it favours that spelling). OOV = alphabetic tokens of length >= 4 not in vocab. Split = adjacent tokens x y where x+y is in vocab but x or y is not. Mixed-case = a lowercase letter, then a capital, inside a word (`ceUs`). Junk = characters outside printable ASCII and common punctuation.\n")
print("| run | tokens | OOV rate | split-word pairs | ligature codepoints | mixed-case garble | junk chars | end-of-line hyphen breaks |\n|---|---|---|---|---|---|---|---|")
for n, r in a_runs.items():
    t = " ".join(v["text"] for v in r["pages"].values())
    tk = re.findall(r"[A-Za-z]+", t); low = [w.lower() for w in tk]
    cand = [w for w in low if len(w) >= 4]
    oov = sum(w not in vocab for w in cand)
    split = sum(1 for x, y in zip(low, low[1:]) if x + y in vocab and (x not in vocab or y not in vocab))
    lig = sum(c in LIG for c in t); mix = len(re.findall(r"\b[a-z]+[A-Z][a-z]*\b", t))
    junk = sum(1 for c in t if ord(c) > 127 and c not in LIG and c not in "—–’‘“”•·é")
    hy = len(re.findall(r"[a-z]-\n\s*[a-z]", t))
    print(f"| {n} | {len(tk)} | {pct(oov / max(1, len(cand)))} | {split} | {lig} | {mix} | {junk} | {hy} |")

# ---- table pages side by side
print("\n## Table pages (Q17-Q20), extracted text around the table\n")
anchor = {81: "Raceme", 104: "sporangia", 108: "Prothallia", 138: "cellulose"}
for pg, a in anchor.items():
    print(f"\n### Wells PDF page {pg}\n")
    for n, r in a_runs.items():
        if n.endswith(("_t12", "_2cpu")) or str(pg) not in r["pages"]: continue
        lines = [l.rstrip() for l in r["pages"][str(pg)]["text"].split("\n") if l.strip()]
        i = next((j for j, l in enumerate(lines) if a.lower() in l.lower()), 0)
        print(f"{n}:\n```\n" + "\n".join(l[:118] for l in lines[max(0, i - 2):i + 10]) + "\n```")
