"""Retrieval scoring of a chunk list against the sample key (52 entries: 40 questions, 8 not in the lesson, 4 answer-seeking).
A chunk 'contains' a key quote when the whitespace/case/NFKC-normalised quote is inside it, or fuzzy partial ratio >= 88 (so a hyphen break still counts
as the right place; whether the answer TEXT survived is measured separately by `ans`). This makes the score independent of page numbers, which change per layout."""
import json, os, re, sqlite3, unicodedata
from rapidfuzz import fuzz

STOP = set("the and for are was what how why who when where which this that with from you your can does did not ano ang mga para yung kung bakit paano saan sino ito iyan iyon siya sila ako ikaw natin namin nang lang din rin pa po".split())
BUDGET, MAX_CHUNKS = 2048 - 276 - 300, 3  # lesson-token budget and chunk count used by the desktop/phone prototypes
words_of = lambda t: [w for w in re.findall(r"[a-z0-9]+", t.lower()) if len(w) >= 3 and w not in STOP]

def N(s):
    s = unicodedata.normalize("NFKC", s).replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip().lower()

def load_key(path):
    return json.load(open(path, encoding="utf-8"))

def quotes(q):
    t = q["quote"]; return t if isinstance(t, list) else [x for x in t.split(" / ")]

def tokens(q):
    t = q.get("token", q["expected_answer"]); return t if isinstance(t, list) else [t]

def contains(qt, ntext):
    qn = N(qt)
    return qn in ntext or fuzz.partial_ratio(qn, ntext, score_cutoff=88) >= 88

def _fts(chunks):
    db = sqlite3.connect(":memory:")
    db.execute("create virtual table f using fts5(heading, text, tokenize='porter unicode61')")
    for i, c in enumerate(chunks, 1): db.execute("insert into f(rowid,heading,text) values(?,?,?)", (i, c["heading"], c["text"]))
    return db

def bm25_rank(db, question):
    qw = sorted(set(words_of(question)))
    if not qw: return [], 0.0
    rows = db.execute("select rowid, bm25(f) from f where f match ? order by 2", (" OR ".join(f'"{w}"' for w in qw),)).fetchall()
    return [r[0] for r in rows], (-rows[0][1] if rows else 0.0)

def pick(rank, toks):
    out, used = [], 0
    for i in rank:
        if used + toks[i] + 2 > BUDGET or len(out) >= MAX_CHUNKS: break
        out.append(i); used += toks[i] + 2
    return out

def balanced(pos, neg, thr):
    return (sum(s >= thr for s in pos) / len(pos) + sum(s < thr for s in neg) / len(neg)) / 2

def evaluate(chunks, key, vec_rank=None, vec_scores=None, hybrid=False):
    """vec_rank(question)->(ranked chunk ids, top score) enables the vector / hybrid retriever instead of BM25."""
    if not chunks: return {k: 0 for k in ("n", "h1", "h3", "hb", "ans", "twin", "guard5", "guardbest")}
    ntext = [N(c["heading"] + " " + c["text"]) for c in chunks]
    toks = {i: int(len(f"[page {c['page']}] {c['heading']}\n{c['text']}".split()) * 1.4) for i, c in enumerate(chunks, 1)}
    db = _fts(chunks)
    R = dict(h1=[], h3=[], hb=[], ans=[], miss=[], bt={}); best = {}; top = {}; twin = []
    twin_of = {q["id"]: next((o for o in key if o is not q and o["id"][0] == "Q" and q.get("fact", "").startswith("D") and o.get("fact", "")[:2] == q["fact"][:2]), None) for q in key if q["id"][0] == "Q"}
    for q in key:
        if vec_rank is None: rank, top[q["id"]] = bm25_rank(db, q["question"])
        else:
            vr, vtop = vec_rank(q["question"]); top[q["id"]] = vtop
            if hybrid:
                br, _ = bm25_rank(db, q["question"]); sc = {}
                for lst in (br, vr):
                    for r, i in enumerate(lst, 1): sc[i] = sc.get(i, 0) + 1 / (60 + r)
                rank = sorted(sc, key=lambda i: -sc[i])
            else: rank = vr
        if q["id"][0] not in "QA": continue
        has = [[contains(qt, ntext[i - 1]) for i in range(1, len(chunks) + 1)] for qt in quotes(q)]
        anyhas = lambda i: any(h[i - 1] for h in has)
        pos = lambda: next((r for r, i in enumerate(rank, 1) if anyhas(i)), 10 ** 6)
        best[q["id"]] = pos()
        if q["id"][0] != "Q": continue
        t = twin_of[q["id"]]
        if t:  # near-twin: does the chunk holding this question's own fact outrank the one holding the twin's fact?
            thas = [[contains(tq, ntext[i - 1]) for i in range(1, len(chunks) + 1)] for tq in quotes(t)]
            tpos = next((r for r, i in enumerate(rank, 1) if any(h[i - 1] for h in thas)), 10 ** 6)
            twin.append(best[q["id"]] < tpos)
        picked = pick(rank, toks)
        R["h1"].append(best[q["id"]] == 1); R["h3"].append(best[q["id"]] <= 3); R["hb"].append(any(anyhas(i) for i in picked))
        body = N(" ".join(chunks[i - 1]["text"] for i in picked))
        R["ans"].append(all(N(t) in body for t in tokens(q))); 
        if not R["hb"][-1]: R["miss"].append(q["id"])
        R["bt"].setdefault(q["type"], []).append((R["hb"][-1], R["ans"][-1]))
    alltext = N(" ".join(c["text"] for c in chunks)); qs = [q for q in key if q["id"][0] == "Q"]
    surv_tok = 100 * sum(all(N(t) in alltext for t in tokens(q)) for q in qs) / len(qs)  # the answer text survives extraction at all
    surv_quote = 100 * sum(all(N(x) in alltext for x in quotes(q)) for q in qs) / len(qs)  # the quoted sentence survives intact
    pos_s = [top[q["id"]] for q in key if q["id"][0] in "QA"]; neg_s = [top[q["id"]] for q in key if q["id"][0] == "O"]
    thr = sorted(set(pos_s + neg_s))
    gbest = max((balanced(pos_s, neg_s, t) for t in thr), default=0)
    pct = lambda x: 100 * sum(x) / len(x) if x else 0
    return dict(n=len(chunks), h1=pct(R["h1"]), h3=pct(R["h3"]), hb=pct(R["hb"]), ans=pct(R["ans"]), twin=pct(twin), twin_n=len(twin),
                guard5=100 * balanced(pos_s, neg_s, 5.0) if vec_rank is None else None, guardbest=100 * gbest, miss=R["miss"], bt=R["bt"], st=surv_tok, sq=surv_quote)
