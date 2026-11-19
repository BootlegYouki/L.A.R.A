"""Checks an answer key against its PDF: every quote must be on its stated page(s).
    python verify_key.py your_key.json your.pdf
Pages are counted the way `pdftotext -f N -l N` counts them (PDF order, not the printed page numbers)."""
import json, re, subprocess, sys
import config

norm = lambda s: re.sub(r"\s+", " ", s.lower().replace("’", "'").replace("“", '"').replace("”", '"')).strip()


def pages(pdf):
    out = subprocess.run([config.PDFTOTEXT, "-layout", pdf, "-"], capture_output=True, check=True).stdout.decode("utf-8", "replace")
    return [norm(p) for p in out.split("\f")[:-1] or [out]]


def main(key_path, pdf):
    key = json.load(open(key_path, encoding="utf-8"))
    pg = pages(pdf)
    print(f"{len(pg)} pages read from the PDF\n")
    bad = warn = 0
    for k in key:
        if not k.get("page"):  # out_of_scope / answer_seeking entries have no page
            print(f"SKIP  {k['id']} ({k['type']}): no page"); continue
        wanted = k["page"] if isinstance(k["page"], list) else [k["page"]]
        quotes = [q.strip() for q in k["quote"]] if isinstance(k["quote"], list) else [q.strip() for q in re.split(r"\s+/\s+", k["quote"]) if q.strip()] if len(wanted) > 1 else [k["quote"]]  # two-part: a list of quotes, or one string with " / " between them
        errs = []
        for q, p in zip(quotes, wanted + wanted[-1:] * len(quotes)):
            if not 1 <= p <= len(pg): errs.append(f"page {p} does not exist"); continue
            if norm(q) not in pg[p - 1]: errs.append(f"quote not on page {p}: {q[:60]!r}")
        others = [i + 1 for i, t in enumerate(pg) if (i + 1) not in wanted and norm(quotes[0]) in t]
        if errs: bad += 1; print(f"FAIL  {k['id']} ({k['type']}): " + "; ".join(errs))
        else: print(f"PASS  {k['id']} ({k['type']}) page {k['page']}")
        if others: warn += 1; print(f"  WARN  the same quote also appears on page(s) {others}: a retrieval hit there would be correct, but the key says {k['page']}")
    print(f"\n{len(key)} entries, {bad} FAIL, {warn} WARN")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    if len(sys.argv) != 3: sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
