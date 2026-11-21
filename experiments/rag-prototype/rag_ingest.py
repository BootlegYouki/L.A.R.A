"""Reads a PDF into a separate database file (needs the embedder and LLM running): python rag_ingest.py your.pdf rag_new.db
Then evaluate against it with:  RAG_DB=rag_new.db KEY_FILE=your_key.json python rag_eval.py --key your_key.json"""
import os, sys
if len(sys.argv) != 3: sys.exit(__doc__)
os.environ["RAG_DB"] = os.path.abspath(sys.argv[2])
import rag
print(rag.ingest(open(sys.argv[1], "rb").read()))
