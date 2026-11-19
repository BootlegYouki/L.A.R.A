"""Sentence rerank off/on/compress, retrieval-only (tokenizer + embedder, no generation): python3 rerank_eval.py"""
import json, os, re
import rag

key = {k["id"]: k for k in json.load(open(os.path.join(rag.HERE, "sample", "answer_key.json"), encoding="utf-8"))}
Q = [k for k in key.values() if k["id"][0] == "Q"]
# filler questions from guard_eval.py with the page their paragraph is on
import ast
GF = next(n.value for n in ast.parse(open(os.path.join(rag.HERE, 'guard_eval.py'), encoding="utf-8").read()).body if isinstance(n, ast.Assign) and n.targets[0].id == 'F')
FP = [8, 8, 13, 13, 17, 17, 19, 19, 23, 23, 33, 33, 38, 38, 42, 42, 44, 48, 48, 53, 57, 57, 61, 61, 8, 13, 6]
F = [dict(id=f"F{i + 1:02d}", question=q, page=FP[i]) for i, q in enumerate(ast.literal_eval(GF))]
P = lambda k: set(k["page"]) if isinstance(k["page"], list) else {k["page"]}
PAIR = {"Q12": "Q24", "Q24": "Q12", "Q21": "Q34", "Q34": "Q21", "Q30": "Q39", "Q39": "Q30"}
norm = lambda s: re.sub(r"\s+", " ", s.lower()).replace(",", "")
def has_answer(k, text):
    toks = k["token"] if isinstance(k.get("token"), list) else [k.get("token", k["expected_answer"])]
    t = norm(text)
    return all((all(re.search(r"(?<![\d.])%s(?!\d)" % re.escape(n), t) for n in re.findall(r"\d+(?:\.\d+)?", norm(x))) if re.search(r"\d", x) else norm(x) in t) for x in toks)
res = {}
for mode in ["off", "on", "compress"]:
    for retr in ["hybrid", "vector", "bm25"]:
        rows = []
        for k in Q + F:
            b = rag.build(k["question"], [], retr, 0.0, "V1", mode)  # threshold 0: the guard is not under test
            by = {s["id"]: s for s in b["sources"]}
            rk = b["trace"]["rerank"]["rows"]  # empty when off
            fr = b["final_rank"]
            chunks = {c["id"]: c for c in rag.load()}
            r = dict(id=k["id"], top1=bool(fr) and chunks[fr[0]]["page"] in P(k), grounded=bool(b["grounded_chunk_id"]) and chunks[b["grounded_chunk_id"]]["page"] in P(k),
                     sent_hit=any(s["page"] in P(k) for s in b["sources"]), tokens=sum(s["tokens"] for s in b["sources"]), n=len(b["sources"]),
                     ans=has_answer(k, " ".join(s["text"] for s in b["sources"])) if k["id"][0] == "Q" else None)
            if k["id"] in PAIR:
                right, wrong = P(k), P(key[PAIR[k["id"]]])
                pos = lambda pg: next((j for j, i in enumerate(fr) if chunks[i]["page"] in pg), 99)
                r["pair_ok"] = pos(right) < pos(wrong)
            rows.append(r)
        res[(mode, retr)] = rows
pc = lambda xs: f"{100 * sum(xs) / len(xs):.0f}% ({sum(xs)}/{len(xs)})" if xs else "-"
L = ["# Sentence rerank: off vs on vs compress (retrieval only, V1 prompt budget, no generation)", "",
     "Questions: 40 key + 27 filler. 'right above wrong' = the distractor pair's right page ranks above its twin (6 questions: Q12/Q24, Q21/Q34, Q30/Q39). top-1/grounded = the chunk the opener would name is on the key page. 'answer in sent' = the key answer text is inside the chunks actually sent (compress cuts text, so this is its risk). Tokens = lesson tokens sent.", "",
     "| mode | retriever | top-1 page right (key 40) | top-1 (filler 27) | grounded right (key+filler) | key page sent | right above wrong | answer text in sent (key 40) | avg lesson tokens sent |", "|---|---|---|---|---|---|---|---|---|"]
for (mode, retr), rows in res.items():
    kq = [r for r in rows if r["id"][0] == "Q"]; ff = [r for r in rows if r["id"][0] == "F"]
    L.append(f"| {mode} | {retr} | {pc([r['top1'] for r in kq])} | {pc([r['top1'] for r in ff])} | {pc([r['grounded'] for r in rows])} | {pc([r['sent_hit'] for r in rows])} | {pc([r['pair_ok'] for r in rows if 'pair_ok' in r])} | {pc([r['ans'] for r in kq])} | {sum(r['tokens'] for r in rows) / len(rows):.0f} |")
L += ["", "## Where hybrid top-1 is wrong", ""]
for mode in ["off", "on", "compress"]:
    L.append(f"- {mode}: " + ", ".join(r["id"] for r in res[(mode, "hybrid")] if not r["top1"]))
open(os.path.join(rag.HERE, "rerank_eval_results.md"), "w", encoding="utf-8").write("\n".join(L) + "\n"); print("\n".join(L))
