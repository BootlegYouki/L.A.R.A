#!/usr/bin/env python3
"""Writes the Kotlin unit-test fixture: a tiny lesson plus what the DESKTOP rag.py computes for 5 key questions.

    python3 tools/parity_reference.py lesson.json wells_biology_key.json --desktop <experiments/rag-prototype>

Outputs app/src/test/resources/{fixture_lesson.json, parity_expected.json}. The Kotlin RetrieverParityTest must reproduce them:
FTS5 bm25 scores (real SQLite), Porter stems (SQLite's own porter tokenizer), the sentence rerank order and the chunks picked.
"hybrid" here is BM25 alone (reciprocal rank), because the phone has no embedder in v1.
"""
import argparse, json, os, re, sqlite3, sys, tempfile

ap = argparse.ArgumentParser()
ap.add_argument("lesson"); ap.add_argument("key")
ap.add_argument("--desktop", required=True)
ap.add_argument("--ids", default="Q01,Q02,Q03,Q07,O1", help="key entries to use")
ap.add_argument("--every", type=int, default=11, help="also keep every Nth chunk so the idf is not trivial")
ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources"))
a = ap.parse_args()

os.environ["RAG_DB"] = os.path.join(tempfile.mkdtemp(), "fixture.db")
sys.path.insert(0, os.path.abspath(a.desktop))
import rag, config

lesson = json.load(open(a.lesson, encoding="utf-8"))
key = {k["id"]: k for k in json.load(open(a.key, encoding="utf-8"))}
ids = a.ids.split(",")
pages = set()
for i in ids:
    p = key[i].get("page")
    pages |= set(p if isinstance(p, list) else [p] if p else [])
keep = [c for n, c in enumerate(lesson["chunks"]) if c["page"] in pages or n % a.every == 0]
chunks = []
for n, c in enumerate(keep):
    c = dict(c, id=n + 1, order_index=n)
    c["tokens"] = int(len(rag.piece(c).split()) * 1.4)  # the desktop's own fallback estimate; Kotlin gets it from the fixture
    chunks.append(c)
json.dump({"title": lesson["title"] + " (test fixture)", "chunks": [{k: c[k] for k in ("id", "page", "heading", "text", "tokens")} for c in chunks]},
          open(os.path.join(a.out, "fixture_lesson.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

c = rag.db()
for ch in chunks:
    c.execute("insert into chunks values(?,?,?,?,?,?)", (ch["id"], ch["page"], ch["heading"], ch["order_index"], ch["text"], ch["tokens"]))
    c.execute("insert into chunks_fts(rowid,heading,text) values(?,?,?)", (ch["id"], ch["heading"], ch["text"]))
c.commit()
rag.load = lambda: chunks
by_id = {ch["id"]: ch for ch in chunks}
BUDGET = 2048 - 450 - 300  # window - base prompt tokens (450 = the desktop estimate) - reply

out = []
for i in ids:
    msg = key[i]["question"]
    q = set(rag.words(msg))
    fts = " OR ".join(f'"{w}"' for w in sorted(q))
    bm = {r[0]: -r[1] for r in c.execute("select rowid, bm25(chunks_fts) from chunks_fts where chunks_fts match ? order by 2", (fts,))}
    bm_rank = sorted(bm, key=lambda x: -bm[x])
    hy = {cid: 1 / (60 + r) for r, cid in enumerate(bm_rank, 1)}
    rank = bm_rank
    if len(rag.words(msg)) >= 2: rank, _ = rag.rerank_chunks(msg, bm_rank, by_id, hy)
    top = max(bm.values(), default=0.0)
    out.append({"id": i, "question": msg, "bm25": {str(k): v for k, v in bm.items()}, "bm25_rank": bm_rank, "top_bm25": top,
                "guard_ok": top >= rag.calib()["bm25_min"], "reranked": rank, "picked": rag.pick(rank, by_id, BUDGET), "budget": BUDGET,
                "best_sentence_score": {str(cid): rag.best_sentences(msg, by_id[cid])[0] for cid in bm_rank[:8]}})

# Porter stems as SQLite makes them: one row per raw token in a porter table, read back through fts5vocab.
toks = sorted({t for ch in chunks for t in re.findall(r"[^\W_]+", (ch["heading"] + " " + ch["text"]).lower()) if t.isascii()})
m = sqlite3.connect(":memory:")
m.execute("create virtual table p using fts5(t, tokenize='porter unicode61')")
m.execute("create virtual table v using fts5vocab(p, 'instance')")
for n, t in enumerate(toks, 1): m.execute("insert into p(rowid,t) values(?,?)", (n, t))
stems = {toks[doc - 1]: term for term, doc, _col, _off in m.execute("select term, doc, col, offset from v")}
json.dump({"stems": stems, "questions": out, "token_note": "chunk tokens in the fixture = int(words*1.4); base prompt = 450"},
          open(os.path.join(a.out, "parity_expected.json"), "w"), indent=1)
print(f"{len(chunks)} fixture chunks, {len(out)} questions, {len(stems)} stems")
for o in out: print(o["id"], "top_bm25=%.3f" % o["top_bm25"], "guard_ok", o["guard_ok"], "picked", o["picked"])
