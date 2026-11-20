"""Text-layer extraction (several libraries), automatic cleanup steps and chunkers, as a Hub would run them on an uploaded PDF or DOCX.
extract(name, path) -> ("pages", [str]) or ("units", [unit]);   to_units(pages, flags) -> [unit];   chunk(units, strategy, ...) -> [chunk]
unit = dict(page, kind in h|p|b|t, text).  chunk = dict(page, heading, text)."""
import re, subprocess, unicodedata

# ------------------------------------------------------------------ extractors
def _cli(args):
    def f(path):
        t = subprocess.run(["pdftotext", *args, path, "-"], capture_output=True, text=True, check=True).stdout
        return "pages", t.split("\f")[:-1]
    return f

def _pypdf(path):
    from pypdf import PdfReader
    return "pages", [p.extract_text() or "" for p in PdfReader(path).pages]

def _pdfminer(path):
    from pdfminer.high_level import extract_text
    return "pages", extract_text(path).split("\f")[:-1]

def _plumber(tables):
    def f(path):
        import pdfplumber
        out = []
        with pdfplumber.open(path) as pdf:
            for pg in pdf.pages:
                if not tables: out.append(pg.extract_text() or ""); continue
                tbs = pg.find_tables()  # ruled tables only ("lines" strategy)
                boxes = [t.bbox for t in tbs]
                inside = lambda o: any(b[0] - 1 <= o["x0"] and o["x1"] <= b[2] + 1 and b[1] - 1 <= o["top"] and o["bottom"] <= b[3] + 1 for b in boxes)
                rest = (pg.filter(lambda o: o.get("object_type") != "char" or not inside(o)).extract_text() or "") if boxes else (pg.extract_text() or "")
                rows = ["\n".join(" | ".join((c or "").replace("\n", " ") for c in r) for r in t.extract()) for t in tbs]
                out.append(rest + ("\n\n" + "\n\n".join(rows) if rows else ""))
        return "pages", out
    return f

def _docx(styled_headings=True, bold_headings=True):
    def f(path):
        from docx import Document
        from docx.table import Table
        from docx.text.paragraph import Paragraph
        d, units = Document(path), []
        for el in d.element.body.iterchildren():
            tag = el.tag.split("}")[1]
            if tag == "p":
                p = Paragraph(el, d); t = p.text.strip()
                if not t: continue
                sn = p.style.name or ""
                if styled_headings and sn.startswith("Heading"): units.append(dict(page=0, kind="h", text=t))
                elif sn.startswith("List"): units.append(dict(page=0, kind="b", text=t))
                elif bold_headings and p.runs and all(r.bold for r in p.runs if r.text.strip()) and len(t) < 90 and not t.endswith("."): units.append(dict(page=0, kind="h", text=t))
                else: units.append(dict(page=0, kind="p", text=t))
            elif tag == "tbl":
                rows = [" | ".join(c.text.strip().replace("\n", " ") for c in r.cells) for r in Table(el, d).rows]
                units.append(dict(page=0, kind="t", text="\n".join(rows)))
        return "units", units
    return f

EXTRACTORS = {"pdftotext": _cli([]), "pdftotext-layout": _cli(["-layout"]), "pdftotext-raw": _cli(["-raw"]), "pypdf": _pypdf, "pdfminer": _pdfminer,
              "pdfplumber": _plumber(False), "pdfplumber+tables": _plumber(True),
              "docx": _docx(True, True), "docx-text-only": _docx(False, False)}  # docx-text-only: ignores heading styles and bold (plain text dump)

# ------------------------------------------------------------------ cleanup steps
ALL = ("lig", "dehyph", "hf", "rejoin", "table", "head")
HEAD_RE = re.compile(r"^(Chapter|Unit|Lesson|Part|Section)\s+\d+[.:]?\s|^\d+(\.\d+)+\s+\S|^(Example\s+\d+(\.\d+)?:|Learning objectives$|Check your understanding$|Key ideas$|Summary$)")
BUL_RE = re.compile(r"^(•|-|\*|\d+[.)])\s+")

def to_units(pages, flags=ALL):
    f = set(flags)
    if "lig" in f: pages = [unicodedata.normalize("NFKC", p.replace("\u00ad", "")) for p in pages]
    if "dehyph" in f: pages = [re.sub(r"([A-Za-z]{2,})-[ \t]*\n[ \t]*([a-z]{2,})", r"\1\2", p) for p in pages]
    if "hf" in f and len(pages) >= 5:
        key = lambda l: re.sub(r"\d+", "#", re.sub(r"\s+", " ", l.strip()))
        cnt = {}
        for p in pages:
            for l in {key(x) for x in p.split("\n") if x.strip()}: cnt[l] = cnt.get(l, 0) + 1
        common = {l for l, n in cnt.items() if n >= max(3, 0.4 * len(pages))}
        pages = ["\n".join(l for l in p.split("\n") if key(l) not in common and not re.fullmatch(r"\s*Page \d+\s*", l)) for p in pages]
    units = []
    for pno, p in enumerate(pages, 1):
        lines = p.split("\n"); cur, rows, last = [], [], None
        def flush():
            nonlocal cur, rows
            if cur: units.append(dict(page=pno, kind="p", text=" ".join(cur))); cur = []
            if rows: units.append(dict(page=pno, kind="t", text="\n".join(rows))); rows = []
        for i, raw in enumerate(lines):
            s = raw.strip()
            if not s:
                if cur and "rejoin" in f: units.append(dict(page=pno, kind="p", text=" ".join(cur))); cur = []
                continue
            cells = re.split(r" {3,}", s)
            is_row = "table" in f and not BUL_RE.match(s) and (" | " in s or len(cells) >= 3)
            if is_row:
                if cur: units.append(dict(page=pno, kind="p", text=" ".join(cur))); cur = []
                rows.append(" | ".join(c.strip() for c in cells) if " | " not in s else s); last = "t"; continue
            if rows:
                if re.match(r"^Table \d+", s): rows.append(s); flush(); continue  # caption belongs to the table
                units.append(dict(page=pno, kind="t", text="\n".join(rows))); rows = []
            prev_blank = i == 0 or not lines[i - 1].strip()
            if BUL_RE.match(s):
                flush(); units.append(dict(page=pno, kind="b", text=s)); last = "b"; continue
            if "head" in f and prev_blank and (HEAD_RE.match(s) or (len(s) < 70 and s[0].isupper() and not re.search(r"[.,;:!?]$", s) and i + 1 < len(lines) and len(lines[i + 1].strip()) >= 50)):
                flush(); units.append(dict(page=pno, kind="h", text=s)); last = "h"; continue
            if last == "b" and "rejoin" in f and units and units[-1]["kind"] == "b" and not prev_blank and not HEAD_RE.match(s):
                units[-1]["text"] += " " + s; continue  # wrapped bullet
            if "rejoin" in f: cur.append(s)
            else: units.append(dict(page=pno, kind="p", text=s))
            last = "p"
        flush()
    return units

# ------------------------------------------------------------------ chunkers
def _words(u): return len(u["text"].split())

def chunk(units, strategy="heading", size=180, overlap=1, table_whole=True, min_fill=0.5):
    """strategy: page (the rule from the desktop experiment's rag.py) | heading (sections, page-independent) | window (structure-blind, 30 word overlap).
    size = target words. overlap = paragraphs repeated at the start of the next chunk (heading strategy). table_whole = a table unit is never split.
    min_fill = a heading starts a new chunk only when the current one already holds min_fill*size words; otherwise the short section is merged and its heading kept inline."""
    out = []
    if strategy == "window":
        w, pg = [], []
        for u in units: w += u["text"].split(); pg += [u["page"]] * len(u["text"].split())
        step = max(1, size - 30)
        for i in range(0, max(1, len(w)), step):
            out.append(dict(page=pg[min(i, len(pg) - 1)] if pg else 0, heading="", text=" ".join(w[i:i + size])))
            if i + size >= len(w): break
        return out
    def split_long(u):  # an unbroken text blob (e.g. pypdf output has no blank lines) is cut at sentence ends so chunks stay near `size` words
        if u["kind"] != "p" or _words(u) <= size * 1.3: return [u]
        sents, parts, cur_s = re.split(r"(?<=[.!?])\s+(?=[A-Z0-9\"])", u["text"]), [], []
        for t in sents:
            if cur_s and sum(len(x.split()) for x in cur_s) + len(t.split()) > size: parts.append(" ".join(cur_s)); cur_s = []
            cur_s.append(t)
        if cur_s: parts.append(" ".join(cur_s))
        return [dict(u, text=t) for t in parts]
    units = [x for u in units for x in split_long(u)]
    if not table_whole:  # rows become ordinary paragraphs, so a chunk boundary can fall inside a table
        units = [x for u in units for x in ([dict(u, kind="p", text=r) for r in u["text"].split("\n")] if u["kind"] == "t" else [u])]
    if strategy == "page":
        head = "Introduction"
        for pno in sorted({u["page"] for u in units}):
            cur, words, new, start, body = [], 0, False, head, False
            for u in (x for x in units if x["page"] == pno):
                if u["kind"] == "h":
                    head = u["text"]
                    if not body: start = head
                else: body = True
                cur.append(u["text"]); new = True; words += len(u["text"].split())
                if words >= size and u["kind"] != "h":
                    out.append(dict(page=pno, heading=start, text="\n\n".join(cur))); cur, words, new, start, body = [u["text"]], len(u["text"].split()), False, head, True
            if cur and new: out.append(dict(page=pno, heading=start, text="\n\n".join(cur)))
        return out
    head, cur, words, fresh = "", [], 0, 0  # fresh = units added since the last chunk was emitted (the overlap alone is not a chunk)
    def emit():
        if fresh: out.append(dict(page=cur[0]["page"], heading=head, text="\n\n".join(u["text"] for u in cur)))
    for u in units:
        if u["kind"] == "h":
            if fresh and words < min_fill * size: cur.append(u); words += _words(u); continue  # merge a short section, keep its heading in the text
            emit(); cur, words, fresh, head = [], 0, 0, u["text"]; continue
        w = _words(u); limit = size * (1.5 if u["kind"] == "t" and table_whole else 1)
        if cur and words + w > limit:
            emit(); cur = cur[-overlap:] if overlap else []; words = sum(_words(x) for x in cur); fresh = 0
        cur.append(u); words += w; fresh += 1
    emit()
    return out
