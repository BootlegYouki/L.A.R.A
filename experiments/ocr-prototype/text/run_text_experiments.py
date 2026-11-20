"""Experiments on the laptop:  python run_text_experiments.py <variants_dir> <exp> [...]    exp = extractors | ablation | chunking | embed | speed
Prints markdown tables. Variants: v0_orig.pdf v1_twocol.pdf v2_hyphen.pdf v3_ligature.pdf v4_messy.pdf v5_docx.docx v6_docx_plain.docx"""
import glob, json, os, resource, statistics, sys, time
from pipeline import *
from retrieval_eval import *

D, EXP = sys.argv[1], sys.argv[2:]
KEY = load_key(os.path.join(os.path.dirname(os.path.abspath(__file__)), "key_sample.json"))
VARS = sorted(os.path.basename(p) for p in glob.glob(D + "/v*"))
PDFV = [v for v in VARS if v.endswith(".pdf")]; DOCV = [v for v in VARS if v.endswith(".docx")]
_ex = {}
def extracted(ex, v):
    if (ex, v) not in _ex: _ex[(ex, v)] = EXTRACTORS[ex](os.path.join(D, v))
    return _ex[(ex, v)]
def run(ex, v, flags=ALL, strat="heading", size=250, ov=1, tw=True, mf=0.5, **kw):
    kind, x = extracted(ex, v)
    units = to_units(x, flags) if kind == "pages" else x
    return evaluate(chunk(units, strat, size, ov, tw, mf), KEY, **kw)
COLS = ["hit@1", "hit@3", "hit-in-budget", "answer text in budget", "near-twin (6)", "decline @5.0", "decline best-thr", "chunks", "answer tokens intact anywhere", "quote intact anywhere"]
def row(r): return [f"{r['h1']:.0f}%", f"{r['h3']:.0f}%", f"{r['hb']:.0f}%", f"{r['ans']:.0f}%", f"{r['twin']:.0f}%", f"{r['guard5']:.0f}%" if r['guard5'] is not None else "-", f"{r['guardbest']:.0f}%", str(round(r['n'])), f"{r['st']:.0f}%", f"{r['sq']:.0f}%"]
def avg(rs): return {k: statistics.mean(r[k] for r in rs) for k in ("h1", "h3", "hb", "ans", "twin", "n", "st", "sq")} | {"guard5": statistics.mean(r["guard5"] for r in rs), "guardbest": statistics.mean(r["guardbest"] for r in rs)}
def table(rows, first="config"):
    print(f"| {first} | " + " | ".join(COLS) + " |\n|---|" + "---|" * len(COLS))
    for n, r in rows: print(f"| {n} | " + " | ".join(row(r)) + " |")
    print()
def vs(ex): return PDFV if not ex.startswith("docx") else DOCV
MESSY = lambda vl: [v for v in vl if not v.startswith("v0")]

if "extractors" in EXP:
    print("## Extractors, full cleanup, heading-aware chunks (250 words, 1 paragraph overlap, min-fill 0.5), BM25\n")
    for ex in EXTRACTORS:
        rows = [(f"{ex} / {v}", run(ex, v)) for v in vs(ex)]
        table(rows + [(f"**{ex} / mean of messy variants**", avg([r for n, r in rows if "v0_" not in n]))], "extractor / variant")
if "ablation" in EXP:
    NAMES = dict(lig="ligature fix (NFKC)", dehyph="de-hyphenate", hf="drop repeated header/footer + page numbers", rejoin="rejoin wrapped lines", table="table rows kept as one unit", head="heading detection")
    for ex in ("pdftotext", "pdftotext-layout", "pdftotext-raw", "pypdf", "docx-text-only"):
        vl = MESSY(vs(ex)); print(f"## Ablation, {ex}, mean over {len(vl)} messy variants ({', '.join(v.split('_')[0] for v in vl)}); heading chunks 250/1, BM25\n")
        rows = [("all cleanup on", avg([run(ex, v) for v in vl])), ("no cleanup at all", avg([run(ex, v, ()) for v in vl]))]
        for s in ALL: rows.append((f"all on, minus {NAMES[s]}", avg([run(ex, v, tuple(x for x in ALL if x != s)) for v in vl])))
        for s in ALL: rows.append((f"only {NAMES[s]}", avg([run(ex, v, (s,)) for v in vl])))
        table(rows, "pipeline")
if "chunking" in EXP:
    ex = os.environ.get("CH_EX", "pdftotext")
    vl = MESSY(vs(ex)); print(f"## Chunking, {ex}, full cleanup, mean over messy variants {', '.join(v.split('_')[0] for v in vl)}; BM25\n")
    rows = []
    for strat, size, ov, tw, mf in [("page", 180, 1, True, 0), ("window", 120, 0, True, 0), ("window", 180, 0, True, 0), ("window", 250, 0, True, 0),
                                    ("heading", 120, 0, True, 0), ("heading", 120, 1, True, 0), ("heading", 180, 0, True, 0), ("heading", 180, 1, True, 0),
                                    ("heading", 120, 1, True, 0.5), ("heading", 180, 0, True, 0.5), ("heading", 180, 1, True, 0.5), ("heading", 250, 1, True, 0.5), ("heading", 350, 1, True, 0.5),
                                    ("heading", 180, 1, True, 1.0), ("heading", 180, 1, False, 0.5)]:
        name = f"{strat} {size}w overlap {ov}" + (f" min-fill {mf}" if strat == "heading" else "") + ("" if tw else " (tables may split)")
        rows.append((name, avg([run(ex, v, strat=strat, size=size, ov=ov, tw=tw, mf=mf) for v in vl])))
    table(rows, "chunker")
if "bytype" in EXP:
    print("## By question type: hit-in-budget / answer-text-in-budget, mean over messy variants (heading 250/1, BM25)\n")
    types = ["direct", "paraphrase", "table", "distractor", "two-part"]
    print("| extractor | " + " | ".join(types) + " |\n|---|" + "---|" * len(types))
    for ex in ("pdftotext", "pdftotext-layout", "pdftotext-raw", "pypdf", "pdfminer", "pdfplumber+tables", "docx"):
        vl = MESSY(vs(ex)); rs = [run(ex, v)["bt"] for v in vl]; cells = []
        for t in types:
            xs = [x for r in rs for x in r.get(t, [])]
            cells.append(f"{100 * sum(a for a, _ in xs) / len(xs):.0f}% / {100 * sum(b for _, b in xs) / len(xs):.0f}% (n={len(xs) // len(vl)})")
        print(f"| {ex} | " + " | ".join(cells) + " |")
    print()
if "embed" in EXP:
    from fastembed import TextEmbedding
    import numpy as np
    m = TextEmbedding("BAAI/bge-small-en-v1.5")
    ex = os.environ.get("CH_EX", "pdftotext"); vl = MESSY(vs(ex)); print(f"## BM25 vs bge-small-en-v1.5 (ONNX, CPU) vs hybrid (RRF), {ex}, heading 250/1, messy variants\n")
    res = {"bm25": [], "vector": [], "hybrid": []}; times = []
    for v in vl:
        kind, x = extracted(ex, v); units = to_units(x) if kind == "pages" else x; ch = chunk(units, "heading", 250, 1)
        t = time.time(); E = np.array(list(m.embed([c["heading"] + "\n" + c["text"] for c in ch]))); times.append((len(ch), time.time() - t))
        cache = {}
        def vr(q):
            if q not in cache:
                qv = np.array(list(m.query_embed([q])))[0]; s = E @ qv; cache[q] = ([int(i) + 1 for i in np.argsort(-s)], float(s.max()))
            return cache[q]
        res["bm25"].append(evaluate(ch, KEY)); res["vector"].append(evaluate(ch, KEY, vec_rank=vr)); res["hybrid"].append(evaluate(ch, KEY, vec_rank=vr, hybrid=True))
    rows = []
    for k, rl in res.items():
        a = avg([{**r, "guard5": r["guard5"] if r["guard5"] is not None else 0} for r in rl]); rows.append((k, a))
    table(rows, "retriever"); print("(decline @5.0 is BM25-only and shown as 0 for vector/hybrid; use 'decline best-thr', which sweeps the cosine/BM25 threshold)\n")
    print(f"embedding speed: {sum(n for n, _ in times)} chunks in {sum(t for _, t in times):.1f} s = {sum(n for n, _ in times) / sum(t for _, t in times):.0f} chunks/s; peak RSS so far {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024:.0f} MB\n")
if "speed" in EXP:
    print("## Extraction speed and RAM (this machine), per extractor\n")
    print("| extractor | file | pages | total s | s/page | median ms/page* | peak RSS of the process (MB) |\n|---|---|---|---|---|---|---|")
    base = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    for ex in EXTRACTORS:
        for v in (["v0_orig.pdf", "v4_messy.pdf"] if not ex.startswith("docx") else ["v5_docx.docx"]):
            ts = []
            for _ in range(3):
                t = time.time(); kind, x = EXTRACTORS[ex](os.path.join(D, v)); u = to_units(x) if kind == "pages" else x; c = chunk(u, "heading", 250, 1); ts.append(time.time() - t)
            n = len(x) if kind == "pages" else max(1, sum(len(b["text"]) for b in x) // 2500)
            print(f"| {ex} | {v} | {n}{'' if kind == 'pages' else ' (est.)'} | {min(ts):.2f} | {min(ts) / n:.3f} | - | {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024:.0f} |")
    print("\n*total = extraction + cleanup + chunking, best of 3 runs. Peak RSS is cumulative for this one process (it only grows), so read it as an upper bound per row.")
