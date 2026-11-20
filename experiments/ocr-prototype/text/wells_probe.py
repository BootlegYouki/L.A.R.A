import sys, time, resource
sys.path.insert(0, ".")
from pipeline import *
for ex in ("pdftotext", "pypdf"):
    t = time.time(); kind, x = EXTRACTORS[ex](sys.argv[1]); t1 = time.time() - t; u = to_units(x); c = chunk(u, "heading", 250, 1)
    print(f"{ex} wells.pdf {len(x)} pages: extract {t1:.2f}s, total with cleanup+chunk {time.time() - t:.2f}s, chunks {len(c)}, peak {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024:.0f} MB")
