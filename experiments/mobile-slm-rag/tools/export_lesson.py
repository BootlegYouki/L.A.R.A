#!/usr/bin/env python3
"""Builds lesson.json for the phone app from the desktop RAG experiment's ingest (PR #337: experiments/rag-prototype).

    python3 tools/export_lesson.py wells.pdf lesson.json --desktop /path/to/experiments/rag-prototype
    python3 tools/export_lesson.py --db rag.db lesson.json          # from an ingest the desktop tool already made

The extraction and chunking are the desktop tool's own (rag._ingest: pdftotext, header removal, ~180-word chunks); only the
embedding step is skipped because the phone has no embedder in v1. The PDF is read locally and never committed.
No pip packages; needs `pdftotext` (poppler) when reading a PDF.
"""
import argparse, json, os, sqlite3, sys, tempfile

ap = argparse.ArgumentParser()
ap.add_argument("src", nargs="?", help="PDF to ingest (omit with --db)")
ap.add_argument("out", help="lesson.json to write")
ap.add_argument("--db", help="read chunks from an existing desktop rag.db instead of a PDF")
ap.add_argument("--desktop", default=os.path.join(os.path.dirname(__file__), "..", "..", "rag-prototype"), help="folder with the desktop rag.py")
ap.add_argument("--title", default="")
a = ap.parse_args()

if not a.db:
    if not a.src: sys.exit("give a PDF or --db")
    tmp = tempfile.mkdtemp()
    a.db = os.path.join(tmp, "rag.db")
    os.environ["RAG_DB"] = a.db
    sys.path.insert(0, os.path.abspath(a.desktop))
    try:
        import rag
    except ImportError as e:
        sys.exit(f"cannot import the desktop rag.py from {a.desktop}: {e}")
    rag.ensure_embeddings = lambda c: None  # no embedder here; chunks only
    print(rag._ingest(open(a.src, "rb").read()))
    a.title = a.title or os.path.splitext(os.path.basename(a.src))[0]

c = sqlite3.connect(a.db)
rows = c.execute("select id, page, heading, text from chunks order by order_index").fetchall()
lesson = {"title": a.title or "Lesson", "chunks": [{"id": i, "page": p, "heading": h, "text": t} for i, p, h, t in rows]}
with open(a.out, "w", encoding="utf-8") as f:
    json.dump(lesson, f, ensure_ascii=False, indent=1)
print(f"{len(rows)} chunks -> {a.out}")
