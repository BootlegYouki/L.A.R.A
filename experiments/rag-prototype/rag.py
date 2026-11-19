"""Core of the RAG prototype: ingest, four retrievers, prompt building (guardrails 3.1/3.2)."""
import math, random, json, os, re, sqlite3, struct, subprocess, threading, time, urllib.error, urllib.request
from array import array

import config
from config import HERE
DB_PATH = os.environ.get("RAG_DB", os.path.join(HERE, "rag.db"))
LLM_URL = os.environ.get("LLM_URL", f"http://127.0.0.1:{config.LLM_PORT}")
EMBED_URL = os.environ.get("EMBED_URL", f"http://127.0.0.1:{config.EMBED_PORT}")
EMBED_KIND = config.EMBED_KIND  # bge | nomic
PROMPT_FILE = os.environ.get("PROMPT_FILE", os.path.join(HERE, "prompts", "prompt_v1_contract.txt"))  # byte copy of contracts/ai/socratic_system_prompt.txt
# V1 is the contract file; V2/V3 are experiments kept next to the app, never written into contracts/.
VARIANTS = {"V1": PROMPT_FILE, "V0": os.path.join(HERE, "prompts", "prompt_v0.txt"), "V2": os.path.join(HERE, "prompts", "prompt_v2.txt"), "V3": os.path.join(HERE, "prompts", "prompt_v3.txt"), "V4": os.path.join(HERE, "prompts", "prompt_v4.txt"), "V5": os.path.join(HERE, "prompts", "prompt_v5.txt"), "V6": os.path.join(HERE, "prompts", "prompt_v6.txt")}
CALIB_FILE = os.path.join(HERE, "calibration.json")
NO_MATCH = "(No part of the lesson matches this question.)"
WINDOW, REPLY = 2048, 300
MAX_CHUNKS = config.MAX_CHUNKS  # fewer chunks = shorter prompt = faster first token; hybrid has the right page in its top 3 for 98% of the key
RETRIEVERS = ["keyword", "bm25", "vector", "hybrid"]
STOP = set("the and for are was what how why who when where which this that with from you your can does did not ano ang mga para yung kung bakit paano saan sino ito iyan iyon siya sila ako ikaw natin namin nang lang din rin pa po".split())
# bge wants an instruction on queries only; nomic wants a prefix on both sides.
PREFIX = {"bge": ("Represent this sentence for searching relevant passages: ", ""),
          "nomic": ("search_query: ", "search_document: ")}[EMBED_KIND]
EMBED_NAME = EMBED_KIND


def db():
    c = sqlite3.connect(DB_PATH, timeout=30)
    c.executescript("""
    create table if not exists meta(k text primary key, v text);
    create table if not exists chunks(id integer primary key, page int, heading text, order_index int, text text, tokens int);
    create virtual table if not exists chunks_fts using fts5(heading, text, tokenize='porter unicode61');
    create table if not exists emb(chunk_id int, model text, vec blob, primary key(chunk_id, model));
    """)
    return c


def post(url, body, timeout=300):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def current_model():
    try:
        with urllib.request.urlopen(LLM_URL + "/props", timeout=2) as r:
            return os.path.basename(json.load(r).get("model_path", "")) or None
    except Exception:
        return None


NO_THINK = {"enable_thinking": False}  # Qwen3 / SmolLM3 style templates; other templates ignore it


def tok(text):
    return len(post(LLM_URL + "/tokenize", {"content": text, "parse_special": True})["tokens"])


def system_prompt(lesson, variant="V1"):
    with open(VARIANTS[variant], "rb") as f:  # byte for byte; only the two placeholders are replaced
        t = f.read().decode("utf-8")
    return t.replace("{reply_language}", "English").replace("{lesson_chunks}", lesson)


def piece(c):
    return f"[page {c['page']}] {c['heading']}\n{c['text']}"


# ---------------------------------------------------------------- ingest
def _pdf_pages(data):
    p = subprocess.run([config.PDFTOTEXT, "-layout", "-", "-"], input=data, capture_output=True, check=True)
    return p.stdout.decode("utf-8", "replace").split("\f")[:-1] or [p.stdout.decode("utf-8", "replace")]


def _paragraphs(page):
    """Yield (is_heading, text). Wrapped lines are joined, bullets and table blocks stay separate."""
    out, cur, table = [], [], []

    def flush():
        nonlocal cur, table
        if cur: out.append((False, " ".join(cur)))
        if table: out.append((False, "\n".join(table)))
        cur, table = [], []

    lines = page.split("\n")
    for i, raw in enumerate(lines):
        s = raw.strip()
        if not s:
            if cur: out.append((False, " ".join(cur))); cur = []  # a table survives blank lines between its rows
            continue
        bullet = s.startswith(("•", "-", "*"))
        if bullet:
            flush(); s = re.sub(r"^[•\-*]\s+", "• ", s)
        elif re.search(r"\S {3,}\S", s):  # table row: columns are separated by wide gaps
            if cur: out.append((False, " ".join(cur))); cur = []
            table.append(re.sub(r" {3,}", " | ", s)); continue
        if table: out.append((False, "\n".join(table))); table = []
        prev_blank = i == 0 or not lines[i - 1].strip()
        next_text = i + 1 < len(lines) and lines[i + 1].strip()
        if prev_blank and next_text and len(s) < 70 and not re.search(r"[.,;:!?]$", s) and not bullet:
            flush(); out.append((True, s)); continue
        cur.append(s)
    flush()
    return out


LOCK = threading.RLock()  # one writer at a time: ingest and ensure_embeddings insert the same rows
INGESTING = False
PROGRESS = {"stage": "", "done": 0, "total": 0}
INGEST_ERROR = None


def ingest(data, log=print):
    global INGESTING, INGEST_ERROR
    with LOCK:
        INGESTING, INGEST_ERROR = True, None
        PROGRESS.update(stage="reading the PDF", done=0, total=0)
        try: return _ingest(data)
        except Exception as e:
            INGEST_ERROR = f"{type(e).__name__}: {e}"; raise
        finally: INGESTING = False


def status_info():
    """Chunk and page counts for /api/status. Reads only, never embeds, never waits for the ingest lock."""
    out = {"pages": None, "chunks": 0, "ingesting": INGESTING, "progress": dict(PROGRESS), "ingest_error": INGEST_ERROR}
    if INGESTING or not os.path.exists(DB_PATH): return out
    try:
        c = sqlite3.connect(DB_PATH, timeout=1)
        out["chunks"] = c.execute("select count(*) from chunks").fetchone()[0]
        r = c.execute("select v from meta where k='pages'").fetchone(); out["pages"] = r and r[0]
        c.close()
    except sqlite3.Error:
        out["ingesting"] = True  # database busy: a write is running
    return out


def _ingest(data):
    t0 = time.time()
    pages = _pdf_pages(data)
    # drop running headers/footers: lines identical on >=40% of pages, plus "Page N"
    norm = lambda l: re.sub(r"\s+", " ", l.strip())
    count = {}
    for p in pages:
        for l in {norm(x) for x in p.split("\n") if x.strip()}:
            count[l] = count.get(l, 0) + 1
    common = {l for l, n in count.items() if n >= max(3, 0.4 * len(pages))}
    chunks, heading, order = [], "Introduction", 0
    for pno, p in enumerate(pages, 1):
        p = "\n".join(l for l in p.split("\n") if norm(l) not in common and not re.fullmatch(r"\s*Page \d+\s*", l))
        cur, words, new, start_heading = [], 0, False, heading
        body = False
        for is_h, t in _paragraphs(p):
            if is_h:
                heading = t
                if not body: start_heading = t  # a chunk that opens with a heading is labelled by it, not by the previous page's
            else: body = True
            cur.append(t); new = True
            words += len(t.split())
            if words >= 180 and not is_h:  # ~250-400 tokens
                chunks.append((pno, start_heading, "\n\n".join(cur)))
                cur, words, new, start_heading, body = [t], len(t.split()), False, heading, True  # one-paragraph overlap
        if cur and new:
            chunks.append((pno, start_heading, "\n\n".join(cur)))
    c = db()
    c.executescript("delete from chunks; delete from chunks_fts; delete from emb; delete from meta;")
    for i, (pno, h, text) in enumerate(chunks):
        row = {"heading": h, "page": pno, "text": text}
        n = tok(piece(row)) if LLM_UP() else int(len(piece(row).split()) * 1.4)
        c.execute("insert into chunks values(?,?,?,?,?,?)", (i + 1, pno, h, i, text, n))
        c.execute("insert into chunks_fts(rowid,heading,text) values(?,?,?)", (i + 1, h, text))
    c.execute("insert into meta values('pages',?)", (str(len(pages)),))
    base = tok(system_prompt("")) if LLM_UP() else 450
    c.execute("insert into meta values('base_tokens',?)", (str(base),))
    c.commit()
    ensure_embeddings(c)
    c.close()
    return {"pages": len(pages), "chunks": len(chunks), "seconds": round(time.time() - t0, 1)}


def LLM_UP():
    try:
        urllib.request.urlopen(LLM_URL + "/health", timeout=2).read(); return True
    except Exception:
        return False


def embed_up():
    try:
        urllib.request.urlopen(EMBED_URL + "/health", timeout=2).read(); return True
    except Exception:
        return False


# ---------------------------------------------------------------- embeddings
class EmbedBusy(Exception):
    pass


EMBED_SEM = threading.Semaphore(1)  # the embedder runs one request at a time; never stack more behind it


def embed(texts, query=False):
    """Queries wait at most 20 s for the embedder and fail with EmbedBusy instead of queueing; ingest batches may wait."""
    pre = PREFIX[0] if query else PREFIX[1]
    out = []
    for i in range(0, len(texts), 8):
        batch = [pre + t for t in texts[i:i + 8]]
        if not EMBED_SEM.acquire(timeout=20 if query else 900): raise EmbedBusy("The lesson search is busy, try again in a few seconds.")
        try:
            try:
                r = post(EMBED_URL + "/v1/embeddings", {"input": batch, "model": "x"}, timeout=30 if query else 600)
            except urllib.error.HTTPError:  # too long for the embedder window: retry halved (never retried on a timeout)
                batch = [" ".join(b.split()[: max(1, len(b.split()) // 2)]) for b in batch]
                r = post(EMBED_URL + "/v1/embeddings", {"input": batch, "model": "x"}, timeout=30 if query else 600)
            except (TimeoutError, urllib.error.URLError, OSError) as e:
                raise EmbedBusy("The lesson search is not answering, try again in a few seconds.") from e
        finally:
            EMBED_SEM.release()
        for d in sorted(r["data"], key=lambda d: d["index"]):
            v = d["embedding"]; n = sum(x * x for x in v) ** 0.5 or 1.0
            out.append(array("f", [x / n for x in v]))
    return out


def ensure_embeddings(c):
    with LOCK:
        have = {r[0] for r in c.execute("select chunk_id from emb where model=?", (EMBED_NAME,))}
        todo = [r for r in c.execute("select id,text from chunks order by id") if r[0] not in have]
        PROGRESS.update(stage="embedding the chunks", done=0, total=len(todo))
        for k in range(0, len(todo), 8):  # commit per batch so readers are never blocked for minutes
            part = todo[k:k + 8]
            for (cid, _), v in zip(part, embed([t for _, t in part])):
                c.execute("insert or replace into emb values(?,?,?)", (cid, EMBED_NAME, v.tobytes()))
            c.commit(); PROGRESS["done"] = k + len(part)


_cache = {}


def load():
    """All chunks plus vectors, cached per (db mtime, embedder)."""
    with LOCK: return _load()


def _load():
    key = (os.path.getmtime(DB_PATH) if os.path.exists(DB_PATH) else 0, EMBED_NAME)
    if _cache.get("key") == key:
        return _cache["chunks"]
    c = db(); ensure_embeddings(c)
    vecs = {r[0]: array("f", r[1]) for r in c.execute("select chunk_id,vec from emb where model=?", (EMBED_NAME,))}
    chunks = [dict(id=r[0], page=r[1], heading=r[2], order_index=r[3], text=r[4], tokens=r[5], vec=vecs.get(r[0]))
              for r in c.execute("select id,page,heading,order_index,text,tokens from chunks order by order_index")]
    meta = dict(c.execute("select k,v from meta")); c.close()
    _cache.update(key=key, chunks=chunks, meta=meta)
    return chunks


def meta():
    load(); return _cache["meta"]


# ---------------------------------------------------------------- retrievers
def words(text):
    return [w for w in re.findall(r"[a-z0-9]+", text.lower()) if len(w) >= 3 and w not in STOP]


def score_all(msg, prev_msg="", prev_chunk=None):
    """Scores and rankings for all four retrievers over every chunk."""
    chunks = load()
    q = set(words(msg + " " + prev_msg))
    kw = {c["id"]: len(q & set(re.findall(r"[a-z0-9]+", (c["heading"] + " " + c["text"]).lower()))) for c in chunks}
    order_of = {c["id"]: c["order_index"] for c in chunks}
    kw_rank = sorted(kw, key=lambda i: (-kw[i], order_of[i]))
    if chunks and kw[kw_rank[0]] == 0:  # step 5: nothing matched, continue after the previous grounded chunk
        ids = [c["id"] for c in chunks]
        s = ids.index(prev_chunk) if prev_chunk in ids else 0
        kw_rank = ids[s:] + ids[:s]
    bm = {}
    if q:
        fts = " OR ".join(f'"{w}"' for w in sorted(q))
        c = db()
        bm = {r[0]: -r[1] for r in c.execute("select rowid, bm25(chunks_fts) from chunks_fts where chunks_fts match ? order by 2", (fts,))}
        c.close()
    bm_rank = sorted(bm, key=lambda i: -bm[i])
    qtext = msg if len(msg.split()) >= 4 else (prev_msg + " " + msg).strip()  # "is it 5?" still needs the topic
    te = time.time()
    if qtext not in _qcache: _qcache[qtext] = embed([qtext], query=True)[0]
    qv = _qcache[qtext]; embed_s = time.time() - te
    vec = {c["id"]: sum(a * b for a, b in zip(qv, c["vec"])) for c in chunks}
    vec_rank = sorted(vec, key=lambda i: -vec[i])
    hy = {}
    for lst in (bm_rank, vec_rank):
        for r, i in enumerate(lst, 1):
            hy[i] = hy.get(i, 0) + 1 / (60 + r)
    hy_rank = sorted(hy, key=lambda i: -hy[i])
    return {"scores": {"keyword": kw, "bm25": bm, "vector": vec, "hybrid": hy},
            "rank": {"keyword": kw_rank, "bm25": bm_rank, "vector": vec_rank, "hybrid": hy_rank},
            "top_cosine": max(vec.values()) if vec else 0.0, "top_bm25": max(bm.values(), default=0.0), "query_text": qtext, "qwords": q, "embed_s": embed_s}


def calib():
    try:
        return json.load(open(CALIB_FILE, encoding="utf-8"))[EMBED_NAME]
    except Exception:
        return {"threshold": 0.65, "bm25_min": 5.0}


def default_threshold():
    return float(config.DEFAULT_THRESHOLD) if config.DEFAULT_THRESHOLD else calib()["threshold"]


def guard_ok(sc, threshold):
    """Chunks are sent when the lesson shares real words with the question (top bm25) OR the best cosine is high enough.
    Built to false-accept: the prompt already refuses ungrounded questions (see guard_eval_results.md)."""
    return sc["top_bm25"] >= calib()["bm25_min"] or sc["top_cosine"] >= threshold


# ---------------------------------------------------------------- prompt
def pick(rank, by_id, budget):
    """Highest-ranked chunks while they fit the lesson-token budget."""
    out, used = [], 0
    for i in rank:
        if used + by_id[i]["tokens"] + 2 > budget or len(out) >= MAX_CHUNKS: break
        out.append(i); used += by_id[i]["tokens"] + 2
    return out


RERANK_DEFAULT = config.DEFAULT_RERANK  # off | on | compress
_idf = {}
_qcache = {}  # query embeddings are deterministic; repeated questions (evals, retries) skip the embedder


def stem(w):
    return re.sub(r"(ing|ed|es|s|e)$", "", w) if len(w) > 4 else w


def sentences_of(text):
    return [x.strip() for x in re.split(r"(?<=[.!?])\s+|\n+", text) if len(x.split()) >= 3]


def idf():
    chunks = load()
    if _idf.get("n") is not chunks:
        df = {}
        for c in chunks:
            for w in {stem(x) for x in words(c["heading"] + " " + c["text"])}: df[w] = df.get(w, 0) + 1
        _idf.update(n=chunks, v={w: math.log((len(chunks) + 1) / (d + 0.5)) + 1 for w, d in df.items()}, N=len(chunks))
    return _idf["v"], math.log(_idf["N"] + 1) + 1  # weight of a word the lesson never uses


def best_sentences(message, c):
    """(score, sentence index, sentences): the sentence sharing the most IDF-weighted question words, numbers and dates count 1.5x."""
    v, unseen = idf()
    q = {stem(w) for w in words(message)}
    wt = lambda w: v.get(w, unseen) * (1.5 if any(ch.isdigit() for ch in w) else 1)
    total = sum(wt(w) for w in q) or 1
    sents = sentences_of(c["text"]) or [c["text"]]
    sc = [sum(wt(w) for w in q & {stem(x) for x in words(s)}) / total for s in sents]
    k = max(range(len(sents)), key=lambda i: sc[i])
    return sc[k], k, sents, sc


BLEND = float(os.environ.get("RERANK_BLEND", "0.2"))


def rerank_chunks(message, rank, by_id, hybrid, top=8):
    """Reorders the top candidates by (hybrid score / best hybrid score) + BLEND x best-sentence score; the rest keep their order.
    Sentence score alone lost chunk-level meaning (top-1 fell), the blend keeps it and only breaks near ties such as twin sentences."""
    rows = {}
    for i in rank[:top]:
        b, k, sents, sc = best_sentences(message, by_id[i]); rows[i] = (b, k, sents, sc)
    mx = max((hybrid.get(i, 0) for i in rank[:top]), default=0) or 1
    new = sorted(rank[:top], key=lambda i: (-(hybrid.get(i, 0) / mx + BLEND * rows[i][0]), rank.index(i))) + rank[top:]
    return new, rows


def trim_history(history):
    """Guardrails 3.2: last 6 messages, at most 300 tokens, dropping the oldest whole message first."""
    # the client sends the history, so never trust a role it names: only user/assistant turns, text only, bounded length
    h = [{"role": m["role"], "content": str(m.get("content", ""))[:2000]} for m in history if isinstance(m, dict) and m.get("role") in ("user", "assistant")][-6:]
    while h and sum(tok(m["content"]) for m in h) > 300:
        h.pop(0)
    return h


def page_lookup(message):
    """'page 12' or 'pages 12 to 14' -> those page numbers (at most 5)."""
    m = re.search(r"\bpages?\s+(\d{1,3})(?:\s*(?:to|-|–|through|and)\s*(\d{1,3}))?", message.lower())
    if not m: return []
    a = int(m.group(1)); b = int(m.group(2) or a)
    return list(range(min(a, b), min(max(a, b), min(a, b) + 4) + 1))


# Filipino/Taglish check for the V2/V3 experiments: the model misfired on the English-only line, so the app answers it itself.
# shortcut: function-word list, so a Filipino message with none of these slips through; replace with a real detector if kept.
FIL = set("ano ang ng mga sagot ilan paano bakit saan sino kailan po yung nito natin namin hindi pwede naman lang kasi".split())
ENGLISH_ONLY = "I can only help in English. Please ask your question again in English."
CODE_LANG_GATE = {"V2", "V3", "V4", "V5", "V6"}


def is_filipino(text):
    return bool(FIL & set(re.findall(r"[a-z]+", text.lower())))


_key = None


def expected_for(message):
    """Test-key lookup for the Teacher view only; never sent to the model."""
    global _key
    if _key is None:
        try: _key = json.load(open(os.environ.get("KEY_FILE") or os.path.join(HERE, "sample", "answer_key.json"), encoding="utf-8"))
        except Exception: _key = []
    n = re.sub(r"\W+", " ", message.lower()).strip()
    k = next((k for k in _key if re.sub(r"\W+", " ", k["question"].lower()).strip() == n), None)
    return k and {"id": k["id"], "page": k.get("page"), "quote": k.get("quote"), "expected_answer": k.get("expected_answer")}


def chapter_lookup(message, chunks):
    """'chapter 4' (+ 'review question 2') -> chunk ids of that chapter's review questions, or its first chunks."""
    m = re.search(r"\bchapter\s+(\d{1,2})\b", message.lower())
    if not m: return []
    n = m.group(1)
    if re.search(r"review|question|exercise|check", message.lower()):
        hit = [i for i, c in enumerate(chunks) if re.search(r"Review Questions:\s*Chapter\s+%s\b" % n, c["text"] + " " + c["heading"], re.I)]
        if hit: return [c["id"] for c in chunks[hit[0]:hit[0] + 2]]  # the heading chunk and the one after it
    start = [i for i, c in enumerate(chunks) if re.search(r"^Chapter\s+%s\.[\s\S]{0,120}?Learning objectives" % n, c["text"], re.M)]
    return [c["id"] for c in chunks[start[0]:start[0] + 2]] if start else []


def build(message, history, retriever, threshold, variant="V1", rerank=None):
    rerank = rerank or RERANK_DEFAULT
    """Returns the chat messages plus everything the Sources / background panel shows."""
    T0 = time.time()
    chunks = load(); by_id = {c["id"]: c for c in chunks}
    hist = trim_history(history)
    prev_user = next((m["content"] for m in reversed(history) if m["role"] == "user"), "")
    prev_chunk = next((m.get("chunk_id") for m in reversed(history) if m["role"] == "assistant" and m.get("chunk_id")), None)
    sc = score_all(message, prev_user, prev_chunk)
    t_scored = time.time()
    base = tok(system_prompt("", variant))
    hist_tok = sum(tok(m["content"]) for m in hist)
    budget = WINDOW - base - hist_tok - REPLY
    rank = sc["rank"][retriever]
    pages = [p for p in page_lookup(message) if any(c["page"] == p for c in chunks)]
    chap = chapter_lookup(message, chunks)
    if pages or chap:  # an explicit page/chapter request beats ranking: those chunks go first, and the guard is skipped
        forced = [c["id"] for c in chunks if c["page"] in pages] + chap
        rank = forced + [i for i in rank if i not in forced]
    rr_rows, orig_rank = {}, list(rank)
    if rerank != "off" and not pages and not chap and len(words(message)) >= 2:
        rank, rr_rows = rerank_chunks(message, rank, by_id, sc["scores"]["hybrid"])
    short = sum(c["tokens"] + 2 for c in chunks) <= budget
    blocked = not short and not pages and not chap and not guard_ok(sc, threshold)
    if short:  # step 2: the whole lesson fits, send it all
        picked = [c["id"] for c in chunks]
    elif blocked:
        picked = []
    else:
        picked = pick(rank, by_id, budget)
    best_of = {i: r[0] for i, r in rr_rows.items()}
    if rerank == "compress" and picked and not short:  # best 2-3 sentences + 1 neighbour each, heading kept; the best chunk goes last
        by_id = {i: dict(c) for i, c in by_id.items()}
        for i in picked:
            _b, kb, sents, ss = rr_rows.get(i) or best_sentences(message, by_id[i])
            top3 = sorted(range(len(sents)), key=lambda j: -ss[j])[:3 if len(sents) > 6 else 2]
            keep = sorted({j for t in top3 if ss[t] > 0 for j in (t - 1, t, t + 1) if 0 <= j < len(sents)} or {kb})
            by_id[i]["text"] = "\n".join(sents[j] for j in keep); by_id[i]["tokens"] = tok(piece(by_id[i]))
    status = {}
    used = 0
    for i in rank[:12]:  # budget walk in rank order, for the trace
        if blocked: status[i] = "skipped: below guard"
        elif i in picked: status[i] = "sent"
        else: status[i] = "skipped: over the %d-chunk cap" % MAX_CHUNKS if len(picked) >= MAX_CHUNKS else "skipped: did not fit"
    while True:
        order = sorted(picked, key=(lambda i: (best_of.get(i, 0), -rank.index(i))) if rerank == "compress" and not short else (lambda i: by_id[i]["order_index"]))
        lesson = "\n\n".join(piece(by_id[i]) for i in order) if order else NO_MATCH
        msgs = [{"role": "system", "content": system_prompt(lesson, variant)}, *hist, {"role": "user", "content": message}]
        n = tok(post(LLM_URL + "/apply-template", {"messages": msgs, "chat_template_kwargs": NO_THINK})["prompt"])
        if n + REPLY <= WINDOW or not picked:
            break
        dropped = max(picked, key=rank.index); picked.remove(dropped)  # the real template adds tokens: drop the lowest ranked chunk
        status[dropped] = "skipped: dropped, real chat template did not fit"
    grounded = next((i for i in rank if i in picked), None)
    forced_reply = ENGLISH_ONLY if variant in CODE_LANG_GATE and is_filipino(message) else None
    cand = {r: [{"id": i, "page": by_id[i]["page"], "heading": by_id[i]["heading"], "score": round(sc["scores"][r].get(i, 0), 4), "rank": k + 1}
                for k, i in enumerate(sc["rank"][r][:10])] for r in RETRIEVERS}
    cal = calib()
    bm, cs = sc["top_bm25"], sc["top_cosine"]
    if short: why = "The whole lesson fits the budget, so every chunk was sent and the guard was not used."
    elif chap: why = f"The question names a chapter; its chunks (pages {', '.join(str(by_id[i]['page']) for i in chap)}) go first and the guard is skipped."
    elif pages: why = f"The question names page {', '.join(map(str, pages))}, so those chunks go first and the guard is skipped."
    elif blocked: why = f"best bm25 {bm:.2f} < {cal['bm25_min']} and best cosine {cs:.3f} < {threshold}, so nothing was sent."
    else: why = f"best bm25 {bm:.2f} >= {cal['bm25_min']}" + ("" if bm >= cal["bm25_min"] else f" is too low, but best cosine {cs:.3f} >= {threshold}") + ", so chunks were sent."
    return {"messages": msgs, "prompt_tokens": n, "budget": budget, "base_tokens": base, "history_messages": len(hist),
            "model": current_model(), "rerank": rerank, "final_rank": rank[:12], "guard_tripped": blocked, "page_lookup": pages, "variant": variant, "short_lesson": short, "grounded_chunk_id": grounded,
            "top_cosine": cs, "top_bm25": bm, "forced_reply": forced_reply,
            "trace": {"query": {"vector_text": sc["query_text"], "keyword_text": (message + " " + prev_user).strip(), "keyword_words": sorted(sc["qwords"]),
                                "embed_prefix": PREFIX[0], "page_lookup": pages, "chapter_lookup": [by_id[i]["page"] for i in chap], "retriever": retriever},
                      "candidates": cand,
                      "rerank": {"mode": rerank, "rows": [{"id": i, "page": by_id[i]["page"], "orig_rank": orig_rank.index(i) + 1, "new_rank": rank.index(i) + 1, "score": round(r[0], 3), "sentence": r[2][r[1]][:160]} for i, r in rr_rows.items()]},
                      "guard": {"signal": f"best bm25 >= {cal['bm25_min']} OR best cosine >= threshold", "bm25": round(bm, 3), "bm25_min": cal["bm25_min"],
                                "cosine": round(cs, 3), "threshold": threshold, "tripped": blocked, "why": why},
                      "budget": {"window": WINDOW, "base": base, "history": hist_tok, "reserve": REPLY, "lesson": budget,
                                 "items": [{"id": i, "page": by_id[i]["page"], "heading": by_id[i]["heading"], "tokens": by_id[i]["tokens"], "status": status.get(i, "")} for i in rank[:12]]},
                      "prompt_messages": msgs,
                      "expected": expected_for(message),
                      "timing_ms": {"embed": round(sc["embed_s"] * 1000), "retrieve": round((t_scored - T0 - sc["embed_s"]) * 1000), "tokenize": round((time.time() - t_scored) * 1000)}},
            "sources": [{"id": i, "page": by_id[i]["page"], "heading": by_id[i]["heading"], "text": by_id[i]["text"],
                         "tokens": by_id[i]["tokens"], "grounded": i == grounded,
                         "scores": {r: round(sc["scores"][r].get(i, 0), 4) for r in RETRIEVERS},
                         "ranks": {r: (sc["rank"][r].index(i) + 1 if i in sc["rank"][r] else None) for r in RETRIEVERS}} for i in order]}


GUARDED = {"V2", "V3", "V4", "V5", "V6"}
PAGE_PREFIX = {"V5", "V6"}
OPENERS_H = ['You\'ll find this on page {p}, in "{h}".', 'Page {p} covers this, in the part called "{h}".', 'Open your lesson to page {p}, to "{h}".',
             'This one is on page {p}, under "{h}".', 'Check page {p}, in the section "{h}".', 'The lesson explains this on page {p}, under "{h}".']
OPENERS_P = ['You\'ll find this on page {p}.', 'Page {p} covers this.', 'This one is on page {p}.', 'Check page {p} of the lesson.']  # the app writes "Look at <heading> on page N." itself from retrieval, instantly, so small models cannot forget the page  # V1 (contract baseline) and V0 are never touched by the output guard
NUM = r"\d+(?:[.,]\d+)*"


class Redactor:
    """Hub-side output guard for V2/V3. Drops a sentence that repeats 10+ lesson words in a row or a number that is in
    the lesson text but not in the learner's message, headings or page tags. The model leaked answers even with explicit rules.
    shortcut: numbers and long copies only; a leaked name or term is not caught."""

    def __init__(self, bodies, headings, message, pages=()):
        self.pages, self.cite_dropped = set(pages), 0
        w = lambda t: re.findall(r"[a-z0-9]+", t.lower())
        self.grams = {tuple(x[i:i + 10]) for b in bodies for x in [w(b)] for i in range(max(0, len(x) - 9))}
        self.body_nums = {n.rstrip(".,") for b in bodies for n in re.findall(NUM, b)}
        self.ok_nums = {n.rstrip(".,") for t in [message, *headings] for n in re.findall(NUM, t)}
        self.w, self.buf, self.out, self.dropped = w, "", "", 0
        # paraphrase check: content words (crude stems) of each full source sentence, minus the words the learner already typed
        asked = self.cw(message)
        self.src = [S for b in bodies for sent in re.split(r"(?<=[.!?])\s+|\n+", b) if len(sent.split()) >= 6 and sent.strip()[-1] in ".!?"
                    for S in [self.cw(sent) - asked] if len(S) >= 4]
        self.para_dropped = 0

    @staticmethod
    def cw(t):
        return {re.sub(r"(ing|ed|es|s|e)$", "", w) if len(w) > 4 else w for w in words(t)}

    def bad(self, sent):
        r = self.cw(sent)
        if any(len(r & S) >= 4 and len(r & S) >= 0.5 * len(S) for S in self.src):  # restates most of one source sentence
            self.para_dropped += 1; return True
        cited = {int(n) for m in re.findall(r"\[page (\d+)\]|\bpages?\s+(\d+(?:\s*(?:,|and|to|-)\s*\d+)*)", sent, re.I) for g in m for n in re.findall(r"\d+", g)}
        if cited - self.pages:  # a page the model was never given: invented citation
            self.cite_dropped += 1; return True
        x = self.w(sent)
        if any(tuple(x[i:i + 10]) in self.grams for i in range(max(0, len(x) - 9))): return True
        nums = {n.rstrip(".,") for n in re.findall(NUM, re.sub(r"\[page \d+\]|\bpages? \d+", "", sent))}
        return bool((nums - self.ok_nums) & self.body_nums)

    def feed(self, text, final=False):
        self.buf += text
        parts = re.split(r"(?<=[.!?])\s+|(?<=\n)", self.buf)
        keep = "" if final else parts.pop()  # the last piece may still be growing
        res = ""
        for p in parts:
            if not p.strip(): res += p; continue
            if self.bad(p): self.dropped += 1
            else: res += p + (" " if not p.endswith("\n") else "")
        self.buf = keep
        self.out += res
        return res


def guarded_stream(b):
    """stream_reply for a build() result, with the V2/V3 output guard; done stats carry 'raw' and 'redacted'."""
    guarded = b["variant"] in GUARDED
    if guarded:
        by = {s["id"]: s for s in b["sources"]}
        red = Redactor([s["text"] for s in b["sources"]], [s["heading"] for s in b["sources"]], b["messages"][-1]["content"], {s["page"] for s in b["sources"]})
    raw = ""
    pending = [""]  # the opener is prefilled into the model turn and shown together with the first model text, so the reply reads as one piece
    msgs = b["messages"]
    if b["variant"] in PAGE_PREFIX and b["sources"]:
        g = next((s for s in b["sources"] if s["grounded"]), b["sources"][0])
        tpl = random.Random(b["messages"][-1]["content"]).choice(OPENERS_H if g.get("heading") else OPENERS_P)
        pending[0] = tpl.format(p=g["page"], h=g.get("heading", ""))
        msgs = msgs + [{"role": "assistant", "content": pending[0]}]

    def take(o):
        t = (pending[0] + ("" if o[:1].isspace() else " ") + o) if pending[0] else o
        pending[0] = ""
        return t

    for k, v in stream_reply(msgs):
        if k == "token":
            raw += v
            if guarded:
                o = red.feed(v)
                if o: yield "token", take(o)
            else: yield "token", v
        else:
            if guarded:
                o = red.feed("", final=True)
                if o: yield "token", take(o)
                if not red.out.strip() and b["sources"]:
                    g = next((s for s in b["sources"] if s["grounded"]), b["sources"][0])
                    yield "token", (pending[0] + " Read that part closely and tell me what you find.") if pending[0] else f"Let's look at the lesson together: reread [page {g['page']}] {g['heading']} and tell me what you find."
                v = {**v, "redacted": red.dropped, "cite_removed": red.cite_dropped, "para_removed": red.para_dropped}
            yield "done", {**v, "raw": raw}


def stream_reply(messages):
    """Yield ('token', text) then ('done', stats) from llama-server."""
    t0 = time.time(); first = None; n = 0; timings = {}
    req = urllib.request.Request(LLM_URL + "/v1/chat/completions", json.dumps(
        {"messages": messages, "stream": True, "max_tokens": REPLY, "cache_prompt": True, "chat_template_kwargs": NO_THINK}).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        for line in r:
            line = line.decode().strip()
            if not line.startswith("data:") or line.endswith("[DONE]"): continue
            d = json.loads(line[5:])
            timings = d.get("timings", timings)
            ch = d.get("choices") or []
            t = ch[0].get("delta", {}).get("content") if ch else None
            if t:
                if first is None: first = time.time() - t0
                n += 1
                yield "token", t
    el = time.time() - t0
    yield "done", {"ttft_s": round(first or el, 2), "tokens": n,
                   "tok_per_s": round(timings.get("predicted_per_second") or (n / max(el - (first or 0), 1e-6)), 1),
                   "prompt_eval_ms": round(timings.get("prompt_ms", 0)), "cached_tokens": timings.get("cache_n", 0)}


def reply_text(message, history, retriever, threshold, variant="V1"):
    b = build(message, history, retriever, threshold, variant)
    if b["forced_reply"]: return b, b["forced_reply"], {"ttft_s": 0, "tok_per_s": 0}
    text, stats = "", {}
    for k, v in guarded_stream(b):
        if k == "token": text += v
        else: stats = v
    return b, text, stats
