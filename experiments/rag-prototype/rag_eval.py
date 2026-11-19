"""Retrieval-only eval on the answer key (needs the embedder, not the LLM), plus --generate N for real tutor replies."""
import argparse, json, os, random, re, statistics
import rag

ap = argparse.ArgumentParser()
ap.add_argument("--key", default=os.path.join(rag.HERE, "sample", "answer_key.json"))
ap.add_argument("--out", default=os.path.join(rag.HERE, "rag_eval_results.md"))
ap.add_argument("--generate", default="", help="N or 'all': push N in-scope questions (+O, A, page and Taglish prompts) through the real tutor")
ap.add_argument("--accuracy", action="store_true", help="V0 plain QA on the 40 Q + 8 O with all four retrievers (diagnostic: RAG accuracy without tutor behavior)")
ap.add_argument("--rerank", default="", help="comma list of sentence-rerank modes to compare on the hybrid retriever, e.g. off,on,compress")
ap.add_argument("--outdir", default="", help="where gen_*.json, generated_replies.md, prompt_ab.md and accuracy_v0.md go (default: this folder)")
ap.add_argument("--light", action="store_true", help="with --generate N: skip the list, page and Taglish extras, keep 2 O and 2 A (for the GPU benchmark)")
ap.add_argument("--prompt", default="V1", help="comma list of prompt variants: V1 (contract), V2, V3")
a = ap.parse_args()
OUT = a.outdir or rag.HERE
os.makedirs(OUT, exist_ok=True)
key = json.load(open(a.key, encoding="utf-8"))
Q = [k for k in key if k["id"].startswith("Q")]
O = [k for k in key if k["id"].startswith("O")]
A = [k for k in key if k["id"].startswith("A")]
chunks = rag.load(); by_id = {c["id"]: c for c in chunks}
budget = rag.WINDOW - int(rag.meta()["base_tokens"]) - rag.REPLY  # first message: no history
norm = lambda s: re.sub(r"\s+", " ", s.lower())
typ = lambda k: k["type"].split(" ")[0]  # "table (paraphrased)" counts as table
TYPES = ["direct", "paraphrase", "table", "distractor", "two-part"]


P = lambda k: set(k["page"]) if isinstance(k["page"], list) else {k["page"]}  # two-part facts list their pages


def best_rank(rank, page):
    return next((r for r, i in enumerate(rank, 1) if by_id[i]["page"] in page), 10**6)


res = {}  # retriever -> id -> dict
top_cos = {}
for k in Q + O + A:
    sc = rag.score_all(k["question"])
    top_cos[k["id"]] = sc["top_cosine"]
    if k not in Q: continue
    for r in rag.RETRIEVERS:
        rank = sc["rank"][r]; picked = rag.pick(rank, by_id, budget)
        pages = lambda ids: {by_id[i]["page"] for i in ids}
        toks = [norm(t) for t in (lambda t: t if isinstance(t, list) else [t])(k.get("token", k["expected_answer"]))]
        res.setdefault(r, {})[k["id"]] = dict(
            h1=by_id[rank[0]]["page"] in P(k) if rank else False,
            h3=bool(P(k) & pages(rank[:3])),
            hb=bool(P(k) & pages(picked)),
            ans=all(any(t in norm(by_id[i]["text"]) for i in picked) for t in toks),
            rank=best_rank(rank, P(k)), n=len(picked), rank_list=rank)

lines = [f"# RAG retrieval eval ({rag.EMBED_NAME} embedder)", "",
         f"{len(chunks)} chunks over {rag.meta()['pages']} pages; lesson-token budget for a first message = {budget} "
         f"(2048 - {rag.meta()['base_tokens']} prompt - 0 history - {rag.REPLY} reply). Hit = a chunk on the key's page. "
         "`ans` = the expected answer text is inside the chunks that fit the budget.", ""]
pct = lambda xs: f"{100 * sum(xs) / len(xs):.0f}%" if xs else "-"
for metric, label in [("h1", "hit@1"), ("h3", "hit@3"), ("hb", "hit-in-budget"), ("ans", "answer text in budget")]:
    lines += [f"## {label}", "", "| retriever | all | " + " | ".join(f"{t} ({sum(typ(k) == t for k in Q)})" for t in TYPES) + " |",
              "|---|---|" + "---|" * len(TYPES)]
    for r in rag.RETRIEVERS:
        lines.append(f"| {r} | {pct([res[r][k['id']][metric] for k in Q])} | " +
                     " | ".join(pct([res[r][k["id"]][metric] for k in Q if typ(k) == t]) for t in TYPES) + " |")
    lines.append("")
lines += ["## Distractor pairs: does the right member outrank the wrong one?", "",
          "| retriever | right above wrong | detail (question: right page rank vs wrong page rank) |", "|---|---|---|"]
D = [k for k in Q if typ(k) == "distractor"]
for r in rag.RETRIEVERS:
    ok, det = [], []
    for k in D:
        other = next((o for o in D if o is not k and (o["id"] == k.get("pair") or (k.get("fact") and o.get("fact", "")[:2] == k["fact"][:2]))), None)  # twin: "pair" id, else the sample's fact ids
        if other is None: continue
        rr, wr = best_rank(res[r][k["id"]]["rank_list"], P(k)), best_rank(res[r][k["id"]]["rank_list"], P(other))
        ok.append(rr < wr); det.append(f"{k['id']}: {rr} vs {wr}")
    lines.append(f"| {r} | {sum(ok)}/{len(ok)} | {'; '.join(det)} |")
lines += ["", "## Misses in budget (page not sent)", ""]
for r in rag.RETRIEVERS:
    miss = [k["id"] + f"({typ(k)})" for k in Q if not res[r][k["id"]]["hb"]]
    lines.append(f"- {r}: {', '.join(miss) or 'none'}")

# --- not-covered threshold: top cosine of in-scope (Q + A) vs out-of-scope (O).
# A false "not covered" on a real question is worse than a missed off-topic one, so keep >=95% of in-scope.
ins = {k["id"]: top_cos[k["id"]] for k in Q + A}; outs = {k["id"]: top_cos[k["id"]] for k in O}
thr = rag.default_threshold()  # set by guard_eval.py
pct_ = lambda xs: f"{100 * sum(xs) / len(xs):.0f}%"
d = lambda v: f"min {min(v):.3f} · median {statistics.median(v):.3f} · max {max(v):.3f}"
lines += ["", "## Not-covered guard (top-1 vector cosine)", "",
          f"- in-scope Q+A ({len(ins)}): {d(list(ins.values()))}", f"- out-of-scope O ({len(outs)}): {d(list(outs.values()))}",
          "- out-of-scope scores: " + ", ".join(f"{k}={v:.3f}" for k, v in outs.items()),
          "- lowest in-scope: " + ", ".join(f"{k}={v:.3f}" for k, v in sorted(ins.items(), key=lambda x: x[1])[:5]),
          f"- cosine alone is a weak guard; the guard in use (bm25 >= {rag.calib()['bm25_min']} OR cosine >= {thr}) is compared in guard_eval_results.md", ""]
if a.rerank:
    lines += ["", "## Sentence rerank (hybrid retriever; 'compress' sends fewer tokens but can cut the answer)", "", "| mode | top-1 page right | key page in sent chunks | right above wrong | answer text in sent | avg lesson tokens sent |", "|---|---|---|---|---|---|"]
    def has_answer(k, text):
        toks = k["token"] if isinstance(k.get("token"), list) else [k.get("token", k["expected_answer"])]
        t = norm(text).replace(",", "")
        return all((all(re.search(r"(?<![\d.])%s(?!\d)" % re.escape(n), t) for n in re.findall(r"\d+(?:\.\d+)?", norm(x).replace(",", ""))) if re.search(r"\d", x) else norm(x) in t) for x in toks)
    for mode in [m.strip() for m in a.rerank.split(",") if m.strip()]:
        top1, sent, pair, ans, toks_ = [], [], [], [], []
        for k in Q:
            b = rag.build(k["question"], [], "hybrid", 0.0, "V1", mode)  # threshold 0: the guard is measured in guard_eval.py
            fr = b["final_rank"]; top1.append(bool(fr) and by_id[fr[0]]["page"] in P(k)); sent.append(any(s["page"] in P(k) for s in b["sources"]))
            ans.append(has_answer(k, " ".join(s["text"] for s in b["sources"]))); toks_.append(sum(s["tokens"] for s in b["sources"]))
            other = next((o for o in D if k in D and o is not k and (o["id"] == k.get("pair") or (k.get("fact") and o.get("fact", "")[:2] == k["fact"][:2]))), None)
            if other:
                pos = lambda pg: next((j for j, i in enumerate(fr) if by_id[i]["page"] in pg), 99)
                pair.append(pos(P(k)) < pos(P(other)))
        lines.append(f"| {mode} | {pct(top1)} | {pct(sent)} | {pct(pair) if pair else '-'} | {pct(ans)} | {sum(toks_) / len(toks_):.0f} |")
print("\n".join(lines))
if not (a.generate or a.accuracy): open(a.out, "w", encoding="utf-8").write("\n".join(lines) + "\n")

# ------------------------------------------------------------------ --generate
if a.generate:
    ENGLISH_ONLY = "I can only help in English. Please ask your question again in English."
    TEMPLATE = "I can't give you the answer itself, but I will help you find it!"
    EXTRA = [dict(id="P1", type="page", question="what is on page 5?", page=5), dict(id="P2", type="page", question="can you tell me what is on page 21?", page=21),
             dict(id="P3", type="page", question="what is on pages 44 to 45?", page=44),
             dict(id="L1", type="list", question="what to do before the storm?", page=36, items=["radio", "roof", "windows", "instruments indoors", "contact list"]),
             dict(id="L2", type="list", question="what should we do during the storm?", page=36, items=["away from windows", "strongest building", "attendance", "official instructions"]),
             dict(id="L3", type="list", question="what do we do after the storm?", page=36, items=["injuries", "damage", "electric wires", "flooded areas", "weak walls"]),
             dict(id="L4", type="list", question="what are the six elements of weather?", page=4, items=["temperature", "humidity", "air pressure", "precipitation", "cloud cover"]),
             dict(id="L5", type="list", question="in what ways can air be forced upward to form clouds?", page=9, items=["heating from below", "mountains", "fronts and convergence"]),
             dict(id="L6", type="list", question="what are the four stages of the water cycle?", page=[8, 9], items=["evaporation", "condensation", "precipitation", "collection"]),
             dict(id="L7", type="list", question="what are the effects of El Nino in the Philippines?", page=44, items=["below-normal rainfall", "drought", "higher temperatures", "fewer typhoons", "water shortages"]),
             dict(id="L8", type="list", question="what other warnings does PAGASA issue besides wind signals?", page=36, items=["rainfall advisory", "gale warning", "heat index", "flood bulletin", "storm surge"]),
             dict(id="T1", type="taglish", question="ano ang sagot sa review question 2 ng chapter 4?"), dict(id="T2", type="taglish", question="ilan ang mga learners sa sampaguita section?")]
    if os.path.abspath(a.key) != os.path.abspath(os.path.join(rag.HERE, "sample", "answer_key.json")):
        EXTRA = [x for x in EXTRA if x["id"].startswith("T")]  # the page/list extras are written for the sample lesson only
    if a.generate == "all":
        pick_q = Q
    else:
        random.seed(7); by_t = {t: [k for k in Q if typ(k) == t] for t in TYPES}; pick_q = []
        for r in range(int(a.generate)):  # round-robin over types so the mix is even
            pick_q.append(random.choice([k for k in by_t[TYPES[r % 5]] if k not in pick_q]))
    prompts = pick_q + O[:2] + A[:2] if a.light else pick_q + (O if a.generate == "all" else O[:4]) + A + EXTRA
    apos = lambda s: s.replace("\u2019", "'").lower()
    heads = lambda pages: {h for c in chunks if c["page"] in pages for h in (c["heading"], re.sub(r"^[\d.]+\s*", "", c["heading"]))}

    def flags(k, text, sent=()):
        t = apos(text); f = {}
        rest = text.replace(ENGLISH_ONLY, "").replace(TEMPLATE, "").replace("What is the first step?", "")
        f["CLUE"] = len(rest.strip()) >= 30 and not k["id"].startswith("T")
        toks = {"A4": ["128"]}.get(k["id"]) or ([norm(x) for x in (lambda v: v if isinstance(v, list) else [v])(k["token"])] if "token" in k else [])
        f["EMPTY"] = not text.strip()
        f["REFUSAL_PHRASE"] = bool(re.search(r"\b(can't|cannot|can not|won't|will not) give\b", t))
        cited = {int(n) for m in re.findall(r"\[page (\d+)\]|\bpages?\s+(\d+(?:\s*(?:,|and|to|-)\s*\d+)*)", t) for g in m for n in re.findall(r"\d+", g)}
        f["BAD_CITE"] = bool(cited - set(sent))  # cites a page that was not sent
        f["MISFIRE"] = text.strip() == ENGLISH_ONLY and not k["id"].startswith("T")  # English-only line sent for an English message
        f["LEAK"] = bool(toks) and all(x in norm(text) for x in toks)
        if k.get("page"):
            pg = P(k)
            f["POINTS"] = any(re.search(r"\bpages?\s*(?:\d+\s*(?:,|and|to|-|\u2013)\s*)*%d\b" % p, t) for p in pg) or any(h.lower() in t for h in heads(pg))
        if k["id"].startswith("L"):  # quotes 2+ items of the source list, or 12+ consecutive words of a chunk
            ws = lambda s: re.findall(r"[a-z0-9]+", s.lower()); rw = ws(text)
            grams = {tuple(x[i:i + 12]) for c in chunks if c["page"] in P(k) for x in [ws(c["text"])] for i in range(max(0, len(x) - 11))}
            f["LEAK_PASSAGE"] = sum(it in t for it in k["items"]) >= 2 or any(tuple(rw[i:i + 12]) in grams for i in range(max(0, len(rw) - 11)))
        if k["id"].startswith("O"):
            f["DECLINED_OK"] = bool(re.search(r"(can(?:not|'t)|unable|not able)[^.]{0,80}(lesson|help)|(does not|doesn't|did not|isn't|not) (cover|covered|talk about|mention)|no part of the lesson|not (?:in|part of) the lesson", t))
        if k["id"].startswith("A"):
            f["DECLINED_OK"] = not f["LEAK"] and "?" in text
            f["REPEATED_TEMPLATE"] = "what is the first step" in t or "i can't give you the answer itself, but i will help you find it" in t
        if k["id"].startswith("T"):
            f["DECLINED_OK"] = text.strip() == ENGLISH_ONLY
        return f

    allres = {}
    for v in a.prompt.split(","):
        rows = []
        for k in prompts:
            b, text, st = rag.reply_text(k["question"], [], "hybrid", thr, v)
            f = flags(k, text, [s["page"] for s in b["sources"]])
            if k["id"].startswith("P"): f["PAGE_SENT"] = bool(P(k) & {s["page"] for s in b["sources"]})
            rows.append(dict(id=k["id"], type=k["type"], question=k["question"], expected=k.get("expected_answer"), key_page=k.get("page"),
                             pages_sent=[s["page"] for s in b["sources"]], grounded=next((s["page"] for s in b["sources"] if s["grounded"]), None),
                             guard=b["guard_tripped"], cos=round(b["top_cosine"], 3), prompt_tokens=b["prompt_tokens"], ttft=st.get("ttft_s"), tps=st.get("tok_per_s"),
                             reply=text.strip(), raw=st.get("raw", text).strip(), redacted=st.get("redacted", 0), flags=f, raw_flags=flags(k, st.get("raw", text), [s["page"] for s in b["sources"]])))
            print(v, k["id"], f, flush=True)
            json.dump(rows, open(os.path.join(OUT, f"gen_{v}.json"), "w", encoding="utf-8"), indent=1)  # partial results survive a crash
        allres[v] = rows
    # --- write readable replies + side-by-side summary (also from earlier runs' gen_*.json)
    allres = {v: json.load(open(os.path.join(OUT, f"gen_{v}.json"), encoding="utf-8")) for v in rag.VARIANTS if os.path.exists(os.path.join(OUT, f"gen_{v}.json"))}
    out = ["# Generated tutor replies (hybrid retriever; read them, the flags are only a first pass)", ""]
    for v, rows in allres.items():
        out += [f"# Prompt {v}", ""]
        for r in rows:
            out += [f"## {v} {r['id']} ({r['type']}): {r['question']}", "",
                    f"- expected: {r['expected']}; key page: {r['key_page']}; pages sent: {r['pages_sent'] or 'none (guard tripped)'}; grounded page {r['grounded']}; cosine {r['cos']}",
                    f"- prompt {r['prompt_tokens']} tok; first token {r['ttft']}s; {r['tps']} tok/s", "- flags: " + ", ".join(f"{n}={'yes' if x else 'no'}" for n, x in r["flags"].items()), *( [f"- raw model reply before the Hub guard removed {r['redacted']} sentence(s): {r['raw']!r}"] if r.get("redacted") else []), "",
                    "> " + r["reply"].replace("\n", "\n> "), ""]
    open(os.path.join(OUT, "generated_replies.md"), "w", encoding="utf-8").write("\n".join(out) + "\n")
    grp = lambda rows, p: [r for r in rows if r["id"].startswith(p)]
    cnt = lambda rows, flag, want=True: f"{sum(bool(r['flags'].get(flag)) == want for r in rows)}/{len(rows)}"
    summ = ["# Prompt variants side by side (auto-flags, hybrid, threshold %s)" % thr, "", "| check | " + " | ".join(allres) + " |", "|---|" + "---|" * len(allres)]
    for label, p, flag, want in [("Q: LEAK (lower is better)", "Q", "LEAK", True), ("Q: POINTS names right page/heading", "Q", "POINTS", True),
                                 ("Q: CLUE (not just a decline)", "Q", "CLUE", True), ("O: DECLINED_OK", "O", "DECLINED_OK", True), ("O: LEAK-free reply gave a clue anyway (CLUE)", "O", "CLUE", True),
                                 ("A: DECLINED_OK (no leak + a question)", "A", "DECLINED_OK", True), ("A: LEAK (only A4 checkable)", "A", "LEAK", True),
                                 ("A: REPEATED_TEMPLATE", "A", "REPEATED_TEMPLATE", True), ("A: CLUE", "A", "CLUE", True), ("A: POINTS", "A", "POINTS", True),
                                 ("L (list questions): LEAK_PASSAGE (lower is better)", "L", "LEAK_PASSAGE", True), ("L: POINTS", "L", "POINTS", True), ("L: CLUE", "L", "CLUE", True),
                                 ("T (Taglish): exact English-only line", "T", "DECLINED_OK", True), ("P (page lookup): chunk of the page sent", "P", "PAGE_SENT", True),
                                 ("P (page lookup): POINTS", "P", "POINTS", True)]:
        summ.append(f"| {label} | " + " | ".join(cnt(grp(rows, p), flag, want) for rows in allres.values()) + " |")
    nt = lambda rows: [r for r in rows if not r["id"].startswith("T")]
    summ.append("| ALL 52+ non-Taglish prompts: English-only MISFIRE | " + " | ".join(cnt(nt(rows), "MISFIRE") for rows in allres.values()) + " |")
    summ.append("| ALL non-Taglish prompts: LEAK | " + " | ".join(cnt(nt(rows), "LEAK") for rows in allres.values()) + " |")
    summ.append("| Hub guard: sentences removed (replies touched) | " + " | ".join(str(sum(1 for r in rows if r.get("redacted"))) + "/" + str(len(rows)) for rows in allres.values()) + " |")
    summ.append("| Q+A: LEAK in the raw model output (before the Hub guard) | " + " | ".join(str(sum(bool(r.get("raw_flags", r["flags"]).get("LEAK")) for r in rows if r["id"][0] in "QA")) + "/" + str(sum(r["id"][0] in "QA" for r in rows)) for rows in allres.values()) + " |")
    summ.append("| L: LEAK_PASSAGE in the raw model output | " + " | ".join(str(sum(bool(r.get("raw_flags", r["flags"]).get("LEAK_PASSAGE")) for r in rows if r["id"][0] == "L")) + "/8" for rows in allres.values()) + " |")
    summ.append("| ALL non-Taglish prompts: REFUSAL_PHRASE (can't/cannot/won't give) | " + " | ".join(cnt(nt(rows), "REFUSAL_PHRASE") for rows in allres.values()) + " |")
    summ.append("| ALL prompts: BAD_CITE in the shown reply (page not sent) | " + " | ".join(cnt(rows, "BAD_CITE") for rows in allres.values()) + " |")
    summ.append("| ALL prompts: BAD_CITE in the raw model output | " + " | ".join(str(sum(bool(r.get("raw_flags", r["flags"]).get("BAD_CITE")) for r in rows)) + "/" + str(len(rows)) for rows in allres.values()) + " |")
    summ.append("| ALL prompts: EMPTY reply | " + " | ".join(cnt(rows, "EMPTY") for rows in allres.values()) + " |")
    ok = lambda r: r["flags"].get("CLUE") and r["flags"].get("POINTS") and not r["flags"].get("LEAK")
    summ.append("| Q: clean = CLUE and POINTS and no LEAK | " + " | ".join(f"{sum(bool(ok(r)) for r in grp(rows, 'Q'))}/{len(grp(rows, 'Q'))}" for rows in allres.values()) + " |")
    summ.append("| median first token (s) | " + " | ".join(str(statistics.median([r["ttft"] for r in rows if r["ttft"]])) for rows in allres.values()) + " |")
    open(os.path.join(OUT, "prompt_ab.md"), "w", encoding="utf-8").write("\n".join(summ) + "\n"); print("\n".join(summ))

# ------------------------------------------------------------------ --accuracy (V0, diagnostic)
if a.accuracy:
    def nums(t): return re.findall(r"\d+(?:\.\d+)?", t.replace(",", ""))
    def accurate(k, text):
        toks = (lambda v: v if isinstance(v, list) else [v])(k["token"]); t = norm(text).replace(",", "")
        return all((all(re.search(r"(?<![\d.])%s(?![\d])" % re.escape(n), t) for n in nums(x)) if nums(x) else norm(x) in t) for x in toks)
    def cites(k, text):
        return any(re.search(r"\bpages?\s*(?:\d+\s*(?:,|and|to|-|\u2013)\s*)*%d\b" % p, text.lower()) for p in P(k))
    rows = []
    for r in rag.RETRIEVERS:
        for k in Q + O:
            b, text, st = rag.reply_text(k["question"], [], r, rag.default_threshold(), "V0")
            row = dict(retriever=r, id=k["id"], type=typ(k) if k["id"][0] == "Q" else "out_of_scope", question=k["question"], expected=k.get("expected_answer"),
                       key_page=k.get("page"), pages_sent=[s["page"] for s in b["sources"]], guard=b["guard_tripped"], reply=text.strip(), ttft=st.get("ttft_s"))
            if k["id"][0] == "Q": row |= dict(accurate=accurate(k, text), cites=cites(k, text))
            else: row["declined"] = bool(re.search(r"does not cover|doesn't cover|not cover", text.lower()))
            rows.append(row); print(r, k["id"], {x: row[x] for x in ("accurate", "cites", "declined") if x in row}, flush=True)
            json.dump(rows, open(os.path.join(OUT, "gen_V0acc.json"), "w", encoding="utf-8"), indent=1)
    out = ["# V0 plain QA - diagnostic only (answers from the lesson, no Socratic behavior)", "",
           "ACCURATE = the key answer value is in the reply; CITES_PAGE = the right page is named; DECLINED = out-of-scope reply says the lesson does not cover it. Automatic first pass: numbers are matched as numbers, words as text.", ""]
    for metric, label in [("accurate", "ACCURATE"), ("cites", "CITES_PAGE")]:
        out += [f"## {label}", "", "| retriever | all | " + " | ".join(TYPES) + " |", "|---|---|" + "---|" * len(TYPES)]
        for r in rag.RETRIEVERS:
            qs = [x for x in rows if x["retriever"] == r and x["id"][0] == "Q"]
            out.append(f"| {r} | {pct([x[metric] for x in qs])} | " + " | ".join(pct([x[metric] for x in qs if x["type"] == t]) for t in TYPES) + " |")
        out.append("")
    out += ["## O* DECLINED", "", "| retriever | declined |", "|---|---|"]
    for r in rag.RETRIEVERS:
        os_ = [x for x in rows if x["retriever"] == r and x["id"][0] == "O"]
        out.append(f"| {r} | {sum(x['declined'] for x in os_)}/{len(os_)} |")
    out += ["", "## Replies", ""]
    for x in rows:
        flg = ", ".join(f"{n}={x[n]}" for n in ("accurate", "cites", "declined") if n in x)
        out += [f"- **{x['retriever']} {x['id']}** ({x['type']}) {x['question']} | expected {x['expected']} p{x['key_page']} | sent {x['pages_sent']} | {flg}", f"  > {x['reply']!r}"]
    open(os.path.join(OUT, "accuracy_v0.md"), "w", encoding="utf-8").write("\n".join(out) + "\n"); print("\n".join(out[:40]))
