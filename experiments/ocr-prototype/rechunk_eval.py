"""End-to-end: chunk a full-book text (one string per page) with the desktop experiment's chunker, rank with BM25, and score
hit@1 / hit@3 / hit-in-budget / answer-text-in-budget on the 27 in-scope questions, as rag_eval.py does.
usage: python rechunk_eval.py pdftotext|<engine> ...   (reads OCR_OUT/<engine>_Afull.json, or runs pdftotext -layout on wells.pdf for 'pdftotext')
The chunker below is copied from experiments/rag-prototype/rag.py (PR #337, _paragraphs + the loop in _ingest); only the database is replaced by an in-memory SQLite FTS5 table.
Chunk token counts are estimated as words*1.4 (the desktop code's fallback when no LLM server runs); the desktop run used the real tokenizer."""
import json, os, re, sqlite3, subprocess, sys
from common import *

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


def chunk(pages):
    """pages: list of page texts (index 0 = page 1). Same rules as rag._ingest."""
    norm_l = lambda l: re.sub(r"\s+", " ", l.strip())
    count = {}
    for p in pages:
        for l in {norm_l(x) for x in p.split("\n") if x.strip()}:
            count[l] = count.get(l, 0) + 1
    common_lines = {l for l, n in count.items() if n >= max(3, 0.4 * len(pages))}
    chunks, heading = [], "Introduction"
    for pno, p in enumerate(pages, 1):
        p = "\n".join(l for l in p.split("\n") if norm_l(l) not in common_lines and not re.fullmatch(r"\s*Page \d+\s*", l))
        cur, words, new, start_heading = [], 0, False, heading
        body = False
        for is_h, t in _paragraphs(p):
            if is_h:
                heading = t
                if not body: start_heading = t
            else: body = True
            cur.append(t); new = True
            words += len(t.split())
            if words >= 180 and not is_h:
                chunks.append((pno, start_heading, "\n\n".join(cur)))
                cur, words, new, start_heading, body = [t], len(t.split()), False, heading, True
        if cur and new:
            chunks.append((pno, start_heading, "\n\n".join(cur)))
    return chunks


STOP = set("the and for are was what how why who when where which this that with from you your can does did not ano ang mga para yung kung bakit paano saan sino ito iyan iyon siya sila ako ikaw natin namin nang lang din rin pa po".split())
words_of = lambda text: [w for w in re.findall(r"[a-z0-9]+", text.lower()) if len(w) >= 3 and w not in STOP]
BUDGET, MAX_CHUNKS = 2048 - 276 - 300, 3  # first-message lesson-token budget measured in the desktop run; MAX_CHUNKS default


def evaluate(pages):
    ch = chunk(pages)
    db = sqlite3.connect(":memory:")
    db.execute("create virtual table f using fts5(heading, text, tokenize='porter unicode61')")
    toks = {}
    for i, (pno, h, t) in enumerate(ch, 1):
        db.execute("insert into f(rowid,heading,text) values(?,?,?)", (i, h, t))
        toks[i] = int(len(f"[page {pno}] {h}\n{t}".split()) * 1.4)
    r = dict(h1=[], h3=[], hb=[], ans=[], miss=[])
    for q in key():
        qw = set(words_of(q["question"]))
        rank = [x[0] for x in db.execute("select rowid, bm25(f) from f where f match ? order by 2", (" OR ".join(f'"{w}"' for w in sorted(qw)),))]
        P = set(qpages(q)); pg = lambda ids: {ch[i - 1][0] for i in ids}
        picked, used = [], 0
        for i in rank:
            if used + toks[i] + 2 > BUDGET or len(picked) >= MAX_CHUNKS: break
            picked.append(i); used += toks[i] + 2
        r["h1"].append(bool(rank) and ch[rank[0] - 1][0] in P); r["h3"].append(bool(P & pg(rank[:3]))); r["hb"].append(bool(P & pg(picked)))
        body = norm(" ".join(ch[i - 1][2] for i in picked)).lower()
        r["ans"].append(all(norm(t).lower() in body for t in tokens_of(q)))
        if not r["hb"][-1]: r["miss"].append(q["id"])
    return len(ch), r


if __name__ == "__main__":
    for eng in sys.argv[1:]:
        if eng == "pdftotext":
            t = subprocess.run(["pdftotext", "-layout", PDF["A"], "-"], capture_output=True, text=True, check=True).stdout
            pages = t.split("\f")[:-1]
        else:
            d = json.load(open(os.path.join(OUT, f"{eng}_Afull.json")))["pages"]
            pages = [d[str(p)]["text"] for p in range(1, 255)]
        n, r = evaluate(pages)
        pct = lambda x: f"{100 * sum(x) / len(x):.0f}%"
        print(f"| {eng} | {n} | {pct(r['h1'])} | {pct(r['h3'])} | {pct(r['hb'])} | {pct(r['ans'])} | {', '.join(r['miss']) or '-'} |")
