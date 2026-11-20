"""Builds input C (photocopy-style, image-only PDF of 20 lesson pages) and the ground truth for B/C. Run on the test machine."""
import json, os, subprocess, sys
import numpy as np
from PIL import Image
import img2pdf
from common import *

os.makedirs(WORK, exist_ok=True)
rng = np.random.default_rng(42)
imgs = []
for i, bp in enumerate(B_PAGES, 1):
    png = os.path.join(WORK, f"c_src_{i:02d}")
    subprocess.run(["pdftoppm", "-r", "150", "-f", str(bp), "-l", str(bp), "-gray", "-png", "-singlefile", PDF["B"], png], check=True)
    im = Image.open(png + ".png").convert("L")
    im = im.rotate(1.0, resample=Image.BICUBIC, expand=False, fillcolor=255)  # slight rotation
    a = np.asarray(im, dtype=np.float32)
    a = 40 + a * (190 / 255)  # grey background (white -> ~230) and weaker contrast
    a += rng.normal(0, 14, a.shape)  # gaussian noise
    out = os.path.join(WORK, f"c_{i:02d}.jpg")
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(out, "JPEG", quality=40)
    imgs.append(out); os.remove(png + ".png")
open(PDF["C"], "wb").write(img2pdf.convert(imgs, layout_fun=img2pdf.get_fixed_dpi_layout_fun((150, 150))))

gt = {}
for i, bp in enumerate(B_PAGES, 1):
    gt[str(bp)] = subprocess.run(["pdftotext", "-layout", "-f", str(bp), "-l", str(bp), PDF["B"], "-"], capture_output=True, text=True, check=True).stdout
json.dump(gt, open(os.path.join(WORK, "gt_B.json"), "w"))
print("C.pdf bytes", os.path.getsize(PDF["C"]), "GT chars", sum(len(v) for v in gt.values()))
