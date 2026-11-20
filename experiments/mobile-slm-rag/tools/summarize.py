#!/usr/bin/env python3
"""Prints on-device results as tables to lay beside the desktop REPORT.md (section 4 retrieval, section 6 prompt metrics).

    python3 tools/summarize.py pulled/results.csv pulled/runs.csv
    python3 tools/summarize.py pulled            # every pulled/<model>/ together, one column per run

One column per run (one model + threads + context size). Rates are over the 27 in-scope (Q) and 3 out-of-scope (O) questions.
Auto-flags are a first pass: read the replies in results.csv too, as the desktop report says.
"""
import csv, statistics, sys

import glob, os
if len(sys.argv) == 2 and os.path.isdir(sys.argv[1]):  # "pulled": every pulled/<model>/results.csv + runs.csv together
    rf = sorted(glob.glob(os.path.join(sys.argv[1], "*", "results.csv"))); uf = sorted(glob.glob(os.path.join(sys.argv[1], "*", "runs.csv")))
elif len(sys.argv) == 3: rf, uf = [sys.argv[1]], [sys.argv[2]]
else: sys.exit(__doc__)
rows = [r for f in rf for r in csv.DictReader(open(f, encoding="utf-8", newline=""))]
runs = {r["run_id"]: r for f in uf for r in csv.DictReader(open(f, encoding="utf-8", newline=""))}
ids = list(dict.fromkeys(r["run_id"] for r in rows)) or list(runs)
yes = lambda v: v.strip().lower() == "true"  # the app writes true/false
num = lambda v: float(v) if v not in ("", None) else None
med = lambda xs: f"{statistics.median(xs):.2f}" if xs else "-"

def col(rid):
    rs = [r for r in rows if r["run_id"] == rid]
    q = [r for r in rs if r["id"].startswith("Q")]; o = [r for r in rs if r["id"].startswith("O")]
    n = lambda xs, f: f"{sum(f(r) for r in xs)} / {len(xs)}"
    pct = lambda xs, k: f"{100 * sum(yes(r[k]) for r in xs) / len(xs):.0f}%" if xs else "-"
    clean = lambda r: yes(r["clue"]) and yes(r["points"]) and not yes(r["leak"])
    gen = [r for r in rs if num(r["gen_tokens"])]
    return {
        "hit@1 (page of top-ranked chunk)": pct(q, "top1_hit"),
        "hit-in-budget (key page in chunks sent)": pct(q, "hit_in_budget"),
        "expected-answer text in budget": pct(q, "answer_in_budget"),
        "Q leaks / 27 (lower better)": n(q, lambda r: yes(r["leak"])),
        "Q right page/heading / 27": n(q, lambda r: yes(r["points"])),
        "O declined / 3": n(o, lambda r: yes(r["declined_ok"])),
        "raw Q leaks / 27 (before output guard)": n(q, lambda r: yes(r["raw_leak"])),
        "bad citations / all": n(rs, lambda r: yes(r["bad_cite"])),
        "clean / 27 (clue + points + no leak)": n(q, clean),
        "median TTFT s": med([num(r["ttft_s"]) for r in gen]),
        "median tok/s": med([num(r["tok_per_s"]) for r in gen]),
        "median prompt tokens": med([num(r["prompt_tokens"]) for r in rs]),
    }

def runcol(rid):
    r = runs.get(rid)
    if not r: return {"run row": "missing (killed runs are recorded the next time the app starts)"}
    return {"model": r["model"], "quant": r["quant"], "threads / ctx": f"{r['threads']} / {r['ctx']}", "device": r["device"],
            "status": f"{r['status']} ({r['questions_done']}/{r['questions_total']})", "model load s": r["model_load_s"] or "-",
            "peak PSS MB": r["peak_pss_mb"], "peak Java heap MB": r["peak_java_heap_mb"], "peak native heap MB": r["peak_native_heap_mb"],
            "battery temp before / after C": f"{r['batt_temp_before_c'] or '-'} / {r['batt_temp_after_c'] or '-'}", "killed (exit reason)": r["exit_reason"] or "no"}

for title, f in (("Run setup and cost", runcol), ("Retrieval and prompt V7 (compare REPORT.md sections 4 and 6)", col)):
    tab = {rid: f(rid) for rid in ids}
    keys = list(dict.fromkeys(k for t in tab.values() for k in t))
    print(f"\n## {title}\n\n| metric | " + " | ".join(ids) + " |\n|---|" + "---|" * len(ids))
    for k in keys: print(f"| {k} | " + " | ".join(str(tab[i].get(k, "")) for i in ids) + " |")
