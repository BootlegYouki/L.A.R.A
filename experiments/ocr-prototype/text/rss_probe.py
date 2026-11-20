"""Peak RAM of one extractor in its own process: python rss_probe.py <extractor> <file>  (prints MB; pdftotext is measured with /usr/bin/time -v instead)."""
import resource, sys, time
from pipeline import *
t = time.time(); kind, x = EXTRACTORS[sys.argv[1]](sys.argv[2]); u = to_units(x) if kind == "pages" else x; c = chunk(u, "heading", 250, 1)
print(f"{sys.argv[1]} {sys.argv[2].split('/')[-1]} {time.time() - t:.2f}s peak_rss={resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024:.0f}MB chunks={len(c)}")
