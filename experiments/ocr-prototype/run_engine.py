"""Run one OCR candidate on one input set and write out/<engine>_<set>.json (page texts, per-page seconds, peak RAM).
usage: python run_engine.py <pdftotext|tesseract|rapidocr|deepseek|glm|paddle|lighton|granite> <A|B|C|Afull> [--threads N] [--procs N] [--pages 1,2,3]
Afull = every page of the Wells book (for the end-to-end retrieval test). Rasterising (pdftoppm, 300 dpi) is timed separately from recognition."""
import argparse, concurrent.futures as cf, json, os, re, resource, subprocess, sys, time
from common import *

ap = argparse.ArgumentParser()
ap.add_argument("engine"); ap.add_argument("dataset")
ap.add_argument("--threads", type=int, default=6, help="threads inside one engine instance")
ap.add_argument("--procs", type=int, default=1, help="pages recognised in parallel (tesseract / deepseek)")
ap.add_argument("--pages", default="")
ap.add_argument("--tag", default="")
ap.add_argument("--dpi", type=int, default=300)
a = ap.parse_args()
ds = "A" if a.dataset == "Afull" else a.dataset
pages = [int(x) for x in a.pages.split(",")] if a.pages else (list(range(1, 255)) if a.dataset == "Afull" else PAGES[ds])
os.makedirs(OUT, exist_ok=True); os.makedirs(os.path.join(WORK, "img"), exist_ok=True)
TESS = os.path.join(ROOT, "env", "bin", "tesseract")
LLAMA = os.path.join(ROOT, "llama")
MODEL = os.path.join(ROOT, "models", "DeepSeek-OCR-Q8_0.gguf"); MMPROJ = os.path.join(ROOT, "models", "mmproj-DeepSeek-OCR-Q8_0.gguf")
DS_PROMPT = os.environ.get("DS_PROMPT", "Free OCR.")  # "<|grounding|>Convert the document to markdown." is the other documented prompt
DS_MAXTOK = os.environ.get("DS_MAXTOK", "2048")


def timed(cmd, env=None, inp=None):
    """Run a command under /usr/bin/time -v -> (wall s, peak RSS MB, stdout, stderr)."""
    t = time.time()
    p = subprocess.run(["/usr/bin/time", "-v"] + cmd, capture_output=True, env={**os.environ, **(env or {})})
    wall = time.time() - t
    err = p.stderr.decode("utf-8", "replace")
    m = re.search(r"Maximum resident set size \(kbytes\): (\d+)", err)
    return wall, int(m.group(1)) / 1024 if m else 0.0, p.stdout.decode("utf-8", "replace"), err


def raster(p):
    png = os.path.join(WORK, "img", f"{a.dataset}_{p:04d}_{a.dpi}")
    t = time.time()
    # C is a 150 dpi scan: poppler upscaling its JPEG to 300 dpi takes ~10 s/page, so render at 150 and upscale 2x with Pillow.
    native = a.dpi // 2 if ds == "C" else a.dpi
    subprocess.run(["pdftoppm", "-r", str(native), "-f", str(p), "-l", str(p), "-gray", "-png", "-singlefile", PDF[ds], png], check=True)
    if native != a.dpi:
        from PIL import Image
        im = Image.open(png + ".png"); im.resize((im.width * 2, im.height * 2), Image.BICUBIC).save(png + ".png")
    return png + ".png", time.time() - t


def do_pdftotext(p):
    wall, rss, out, _ = timed(["pdftotext", "-layout", "-f", str(p), "-l", str(p), PDF[ds], "-"])
    return out, dict(ocr_s=wall, raster_s=0.0, rss_mb=rss)


def do_tesseract(p):
    png, rs = raster(p)
    wall, rss, out, _ = timed([TESS, png, "stdout", "-l", "eng", "--psm", "3"], env={"OMP_THREAD_LIMIT": str(a.threads), "TESSDATA_PREFIX": os.path.join(ROOT, "env", "share", "tessdata")})
    os.remove(png)
    return out, dict(ocr_s=wall, raster_s=rs, rss_mb=rss)


_rapid = None
def rapid_lines(res, h):
    """Rebuild text lines from boxes: same row = vertical centres within half a line height; wide gaps become 3 spaces (table cell separator)."""
    items = sorted(((sum(pt[1] for pt in b) / 4, min(pt[0] for pt in b), max(pt[0] for pt in b), max(pt[1] for pt in b) - min(pt[1] for pt in b), t) for b, t in zip(res.boxes, res.txts)))
    rows = []
    for it in items:
        if rows and abs(rows[-1][-1][0] - it[0]) < 0.5 * max(it[3], rows[-1][-1][3]): rows[-1].append(it)
        else: rows.append([it])
    out = []
    for r in rows:
        r.sort(key=lambda x: x[1]); line = r[0][4]
        for prev, cur in zip(r, r[1:]):
            line += ("   " if cur[1] - prev[2] > 2.5 * max(cur[3], 10) else " ") + cur[4]
        out.append(line)
    return "\n".join(out)


def do_rapidocr(p):
    global _rapid
    if _rapid is None:
        from rapidocr import RapidOCR
        _rapid = RapidOCR(params={"Global.log_level": "error", "EngineConfig.onnxruntime.intra_op_num_threads": a.threads, "EngineConfig.onnxruntime.inter_op_num_threads": 1})
    png, rs = raster(p)
    t = time.time(); res = _rapid(png); dt = time.time() - t
    txt = rapid_lines(res, 0) if res.txts else ""
    os.remove(png)
    return txt, dict(ocr_s=dt, raster_s=rs, rss_mb=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)


VLM = {  # engine -> (model, mmproj, prompt, extra llama-mtmd-cli args)
    "deepseek": ("DeepSeek-OCR-Q8_0.gguf", "mmproj-DeepSeek-OCR-Q8_0.gguf", os.environ.get("DS_PROMPT", "Free OCR."), ["--chat-template", "deepseek-ocr"]),
    "glm": ("glm/GLM-OCR-Q8_0.gguf", "glm/mmproj-GLM-OCR-Q8_0.gguf", "Text Recognition:", []),
    "paddle": ("paddle/PaddleOCR-VL-1.6-GGUF.gguf", "paddle/PaddleOCR-VL-1.6-GGUF-mmproj.gguf", "OCR:", []),
    "lighton": ("lighton/LightOnOCR-2-1B-Q8_0.gguf", "lighton/mmproj-LightOnOCR-2-1B-Q8_0.gguf", "", []),
    "granite": ("granite/granite-docling-258M-Q8_0.gguf", "granite/mmproj-granite-docling-258M-Q8_0.gguf", "Convert this page to docling.", []),
}


def do_vlm(p):
    model, mmproj, prompt, extra = VLM[a.engine]
    png, rs = raster(p)
    cmd = [os.path.join(LLAMA, "llama-mtmd-cli"), "-m", os.path.join(ROOT, "models", model), "--mmproj", os.path.join(ROOT, "models", mmproj), "--image", png,
           "-p", prompt, "--temp", "0", "-n", DS_MAXTOK, "-t", str(a.threads), "--no-warmup"] + extra
    wall, rss, out, err = timed(cmd, env={"LD_LIBRARY_PATH": LLAMA})
    os.remove(png)
    enc = sum(int(x) for x in re.findall(r"mtmd batch encoding done in (\d+) ms", err)) / 1000
    out = re.sub(r"<\|ref\|>.*?<\|/ref\|><\|det\|>.*?<\|/det\|>\n?", "", out)  # DeepSeek grounding tags
    return out, dict(ocr_s=wall, raster_s=rs, rss_mb=rss, image_encode_s=enc)


FN = dict(pdftotext=do_pdftotext, tesseract=do_tesseract, rapidocr=do_rapidocr).get(a.engine, do_vlm)
res, t0 = {}, time.time()
if a.procs > 1:
    with cf.ThreadPoolExecutor(a.procs) as ex:
        for p, (txt, m) in zip(pages, ex.map(FN, pages)): res[p] = dict(text=txt, **m)
else:
    for i, p in enumerate(pages, 1):
        txt, m = FN(p); res[p] = dict(text=txt, **m)
        print(f"{a.engine} {a.dataset} page {p} ({i}/{len(pages)}): {m['ocr_s']:.1f}s", flush=True)
wall = time.time() - t0
name = f"{a.engine}_{a.dataset if a.dataset != 'Afull' else 'Afull'}{a.tag}"
json.dump(dict(engine=a.engine, dataset=a.dataset, threads=a.threads, procs=a.procs, dpi=a.dpi, wall_s=wall, peak_rss_mb=max(v["rss_mb"] for v in res.values()),
               pages={str(p): v for p, v in res.items()}), open(os.path.join(OUT, name + ".json"), "w"))
print(f"done {name}: {len(pages)} pages in {wall:.1f}s wall")
