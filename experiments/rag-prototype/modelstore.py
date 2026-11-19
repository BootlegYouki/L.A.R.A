"""Model manifest (models.json), resumable verified downloads, safe deletion. Shared by setup.py and gpu_bench.py. Standard library only."""
import json, os, shutil, sys, time, urllib.error, urllib.request
import config

MANIFEST = json.load(open(os.path.join(config.HERE, "models.json"), encoding="utf-8"))
GB = 2**30


def entry(name):
    n = name.lower()
    for e in MANIFEST:
        if n in (e["name"].lower(), e["file"].lower(), e.get("save_as", "").lower()): return e
    hits = [e for e in MANIFEST if n in e["name"].lower()]
    if len(hits) == 1: return hits[0]
    sys.exit(f"Unknown model '{name}'. Names: " + ", ".join(e["name"] for e in MANIFEST))


def target_dir(e):
    if e["name"].startswith("nomic"): return os.path.dirname(config.EMBED_MODEL)
    return config.BIG_MODELS_DIR if e.get("optional") else config.MODELS_DIR


def target_path(e):
    return os.path.join(target_dir(e), e.get("save_as") or e["file"])


def free_gb(path):
    while path and not os.path.isdir(path): path = os.path.dirname(path)
    return shutil.disk_usage(path or ".").free / GB


def hf_size(e):
    """Byte size from the Hugging Face API (the file names and sizes are never guessed)."""
    try:
        with urllib.request.urlopen(urllib.request.Request(f"https://huggingface.co/api/models/{e['repo']}?blobs=true", headers={"User-Agent": "rag-prototype"}), timeout=60) as r:
            for s in json.load(r)["siblings"]:
                if s["rfilename"] == e["file"]: return s["size"]
    except (OSError, ValueError, KeyError): pass
    return None


def have(e):
    p = target_path(e)
    return os.path.isfile(p) and os.path.getsize(p) >= e["size_bytes"] * 0.999


def download(e):
    """Resumable download of ONE model file. Returns (ok, message). Checks free space first and the final byte size against the API."""
    dest = target_path(e); part = dest + ".part"
    if have(e): return True, "already there"
    need = (e["size_bytes"] - (os.path.getsize(part) if os.path.isfile(part) else 0)) / GB + 1.0
    if free_gb(os.path.dirname(dest)) < need:
        return False, f"SKIPPED (not enough space): needs {need:.1f} GB free in {os.path.dirname(dest)}, only {free_gb(os.path.dirname(dest)):.1f} GB free"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = f"https://huggingface.co/{e['repo']}/resolve/main/{e['file']}"
    for attempt in range(10):
        have_b = os.path.getsize(part) if os.path.isfile(part) else 0
        req = urllib.request.Request(url, headers={"User-Agent": "rag-prototype", **({"Range": f"bytes={have_b}-"} if have_b else {})})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                if have_b and r.status != 206: have_b = 0
                total = have_b + int(r.headers.get("Content-Length", 0)); got = have_b; last = 0; t0 = time.time()
                with open(part, "ab" if have_b else "wb") as f:
                    while True:
                        buf = r.read(1 << 20)
                        if not buf: break
                        f.write(buf); got += len(buf)
                        if time.time() - last > 2:
                            last = time.time()
                            print(f"\r  {e['name']}: {got / 2**20:,.0f} / {total / 2**20:,.0f} MB ({(got - have_b) / 2**20 / max(time.time() - t0, 1e-6):.1f} MB/s)   ", end="", flush=True)
            print(); break
        except (OSError, urllib.error.URLError) as ex:
            print(f"\n  connection problem ({ex}); retry {attempt + 1}/10 in 5 s"); time.sleep(5)
    else:
        return False, "download failed after 10 tries (the partial file is kept; run again to resume)"
    want = hf_size(e) or e["size_bytes"]
    if os.path.getsize(part) != want:
        return False, f"size mismatch: got {os.path.getsize(part)} bytes, the Hugging Face API says {want}; delete {part} and retry"
    os.replace(part, dest)
    return True, "downloaded"


def delete_downloaded(e):
    """Deletes the model file, but only that exact file name and only inside MODELS_DIR / BIG_MODELS_DIR."""
    p = os.path.abspath(target_path(e))
    inside = any(os.path.commonpath([p, os.path.abspath(d)]) == os.path.abspath(d) for d in (config.MODELS_DIR, config.BIG_MODELS_DIR))
    if inside and os.path.basename(p) == (e.get("save_as") or e["file"]) and os.path.isfile(p):
        os.remove(p); return True
    return False
