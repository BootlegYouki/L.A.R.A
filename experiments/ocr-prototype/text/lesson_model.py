"""Turns the sample lesson PDF (ReportLab, single column) into a block model [(kind, payload)] so the same text can be re-rendered
in other layouts. kinds: h1 h2 h3 para bullet table(rows, caption). Source text is never edited; only the layout changes."""
import re, subprocess, sys, json, unicodedata

HDR = re.compile(r"^Weather, Climate and Typhoons in the Philippines\s{3,}Grade 8 Science$")
BOX = re.compile(r"^(Learning objectives|Check your understanding|Key ideas|Example \d+\.\d+:.*|Summary|Remember|Vocabulary)$")

def pages_of(pdf):
    t = subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout
    return t.split("\f")[:-1]

def blocks_of(pdf):
    out = []
    for pno, page in enumerate(pages_of(pdf), 1):
        lines = [l.rstrip() for l in page.split("\n")]
        lines = [l for l in lines if not HDR.match(l.strip()) and not re.fullmatch(r"\s*Page \d+\s*", l)]
        i, cur, table, bl = 0, [], [], None
        def flush_para():
            nonlocal cur
            if cur: out.append(("para", " ".join(cur), pno)); cur = []
        def flush_table(cap=""):
            nonlocal table
            if table: out.append(("table", (table, cap), pno)); table = []
        while i < len(lines):
            raw = lines[i]; s = raw.strip(); i += 1
            if not s:
                flush_para(); bl = None
                continue
            cols = re.split(r" {3,}", s)
            if pno > 2 and len(cols) >= 3 and not s.startswith("•"):  # whitespace table row (page 2 is the contents list)
                flush_para(); table.append(cols); continue
            if table:
                m = re.match(r"^(Table \d+\.\d+\..*)$", s)
                flush_table(m.group(1) if m else "")
                if m: continue
            if s.startswith("•"):
                flush_para(); out.append(("bullet", s[1:].strip(), pno)); bl = len(out) - 1; continue
            if bl is not None and raw.startswith("     ") and out[bl][0] == "bullet":
                out[bl] = ("bullet", out[bl][1] + " " + s, pno); continue
            prev_blank = i == 1 or not lines[i - 2].strip()
            if prev_blank and re.match(r"^Chapter \d+\. ", s) and pno > 2:
                if i < len(lines) and lines[i].strip() and not BOX.match(lines[i].strip()) and not lines[i].strip().startswith(("Learning",)):
                    s += " " + lines[i].strip(); i += 1
                flush_para(); out.append(("h1", s, pno)); continue
            if prev_blank and re.match(r"^\d+\.\d+ \S", s) and len(s) < 90 and not s.endswith("."):
                flush_para(); out.append(("h2", s, pno)); continue
            if prev_blank and BOX.match(s):
                flush_para(); out.append(("h3", s, pno)); continue
            cur.append(s)
        flush_para(); flush_table()
    # a paragraph that continues over a page break is one paragraph
    merged = []
    for b in out:
        if merged and b[0] == "para" and merged[-1][0] == "para" and merged[-1][2] != b[2] and not re.search(r"[.!?:\"”]$", merged[-1][1]) and b[1][:1].islower():
            merged[-1] = ("para", merged[-1][1] + " " + b[1], merged[-1][2])
        else: merged.append(b)
    return merged

def flat(blocks):
    t = []
    for k, p, _ in blocks:
        t.append(" ".join(" ".join(r) for r in p[0]) + " " + p[1] if k == "table" else p)
    return " ".join(t)

def N(s):
    s = unicodedata.normalize("NFKC", s).replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip().lower()

if __name__ == "__main__":
    b = blocks_of(sys.argv[1])
    from collections import Counter
    print(Counter(k for k, _, _ in b))
    txt = N(flat(b)); key = json.load(open(sys.argv[2])); miss = []
    for q in key:
        if q["id"][0] not in "QA": continue
        for qt in (q["quote"] if isinstance(q["quote"], list) else [q["quote"]]):
            qn = N(qt.replace(" / ", " "))
            if qn not in txt and not all(N(x) in txt for x in qt.split(" / ")): miss.append(q["id"])
    print("quotes missing:", miss)
