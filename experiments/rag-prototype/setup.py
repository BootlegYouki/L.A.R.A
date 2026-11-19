"""Installs the tools and the minimum models: llama.cpp b11938, poppler (Windows only), the embedder and the Qwen3-4B baseline.
The other candidate models are NOT downloaded here: the benchmark (bench_all) downloads one at a time and deletes it afterwards.
    python setup.py [--backend vulkan|cpu] [--tools-only] [--install NAME] [--models-dir D:\\models] [--skip-llama] [--skip-poppler]
Resumable: a half-downloaded file continues where it stopped; finished files are skipped. Standard library only."""
import argparse, hashlib, json, os, shutil, sys, tarfile, time, urllib.error, urllib.request, zipfile
import config
import modelstore

TAG = "b11938"
GH = f"https://github.com/ggml-org/llama.cpp/releases/download/{TAG}/"
LLAMA_ASSETS = {  # asset name -> sha256 published in the release
    ("win", "vulkan"): ("llama-b11938-bin-win-vulkan-x64.zip", "9ecd072c6e785fa2b932229c86f845695198195c4f29b99b85ed42dccb022aa6"),
    ("win", "cpu"): ("llama-b11938-bin-win-cpu-x64.zip", "9e2fc62604446d86809ca09094d3c7a7329b723bda636e4bbd23c3ce62cb77dc"),
    ("linux", "vulkan"): ("llama-b11938-bin-ubuntu-vulkan-x64.tar.gz", "9d78c8eae0be03d36665753d1a1a7f360fd18186265f1064e07d8f64eff71f3c"),
    ("linux", "cpu"): ("llama-b11938-bin-ubuntu-x64.tar.gz", "0206adf559a226dfcafadf8051a91f1f813cf53b2ad570b1262658f1fddaefa4"),
}
POPPLER = ("https://github.com/oschwartz10612/poppler-windows/releases/download/v25.12.0-0/Release-25.12.0-0.zip",
           "9499c7474e4deb41c80ef5ea4a18cc1f3843695fbfa3c247db5c46c6eab2e26f")
def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "rag-prototype-setup"}), timeout=60) as r:
        return json.load(r)


def free_gb(path):
    while path and not os.path.isdir(path): path = os.path.dirname(path)
    return shutil.disk_usage(path or ".").free / 2**30


def download(url, dest, sha256=None, expect_size=None):
    """Resumable download with a progress line; verifies the SHA-256 when given. Returns False when the drive is too full."""
    if os.path.isfile(dest) and (expect_size is None or os.path.getsize(dest) == expect_size):
        print(f"  already there: {os.path.basename(dest)}"); return True
    part = dest + ".part"
    if expect_size:
        need = (expect_size - (os.path.getsize(part) if os.path.isfile(part) else 0)) / 2**30 + 0.5
        if free_gb(os.path.dirname(dest)) < need:
            print(f"  SKIPPED {os.path.basename(dest)}: needs {need:.1f} GB free on {os.path.splitdrive(dest)[0] or os.path.dirname(dest)}, only {free_gb(os.path.dirname(dest)):.1f} GB free. Use -ModelsDir on a bigger drive."); return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for attempt in range(8):
        have = os.path.getsize(part) if os.path.isfile(part) else 0
        req = urllib.request.Request(url, headers={"User-Agent": "rag-prototype-setup", **({"Range": f"bytes={have}-"} if have else {})})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                if have and r.status != 206: have = 0  # the server ignored Range: start over
                total = have + int(r.headers.get("Content-Length", 0))
                mode = "ab" if have else "wb"; last = 0; t0 = time.time(); got = have
                with open(part, mode) as f:
                    while True:
                        buf = r.read(1 << 20)
                        if not buf: break
                        f.write(buf); got += len(buf)
                        if time.time() - last > 2:
                            last = time.time(); mb = got / 2**20
                            print(f"\r  {os.path.basename(dest)}: {mb:,.0f} / {total / 2**20:,.0f} MB  ({(got - have) / 2**20 / max(time.time() - t0, 1e-6):.1f} MB/s)   ", end="", flush=True)
            print()
            break
        except (OSError, urllib.error.URLError) as e:
            print(f"\n  connection problem ({e}); retrying in 5 s ({attempt + 1}/8)"); time.sleep(5)
    else:
        sys.exit(f"Download failed: {url}")
    if sha256:
        h = hashlib.sha256()
        with open(part, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
        if h.hexdigest() != sha256: sys.exit(f"SHA-256 mismatch for {os.path.basename(dest)}: got {h.hexdigest()}, expected {sha256}. Delete {part} and retry.")
        print(f"  SHA-256 ok: {h.hexdigest()}")
    os.replace(part, dest)
    return True


def extract(archive, into):
    os.makedirs(into, exist_ok=True)
    if archive.endswith(".zip"):
        with zipfile.ZipFile(archive) as z: z.extractall(into)
    else:
        with tarfile.open(archive) as t: t.extractall(into)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="vulkan", choices=["vulkan", "cpu"])
    ap.add_argument("--tools-only", action="store_true", help="only llama.cpp and poppler, no model files")
    ap.add_argument("--install", default="", help="also download and keep these models (comma list of names from models.json)")
    ap.add_argument("--models-dir", default="", help="folder for the model files (any drive); saved to config.env so run and bench use it")
    ap.add_argument("--skip-llama", action="store_true"); ap.add_argument("--skip-poppler", action="store_true")
    a = ap.parse_args()
    if sys.version_info < (3, 10): sys.exit("Python 3.10 or newer is needed (this is %d.%d)." % sys.version_info[:2])
    plat = "win" if config.IS_WIN else "linux"
    os.makedirs(config.BIN_DIR, exist_ok=True)
    if not a.skip_llama:
        name, sha = LLAMA_ASSETS[(plat, a.backend)]
        print(f"[1/3] llama.cpp {TAG} ({a.backend})")
        if config.LLAMA_SERVER and os.path.isfile(config.LLAMA_SERVER) and config.BIN_DIR in config.LLAMA_SERVER: print("  already installed")
        else:
            z = os.path.join(config.BIN_DIR, name); download(GH + name, z, sha)
            extract(z, os.path.join(config.BIN_DIR, "llama")); os.remove(z)
            for f in __import__("glob").glob(os.path.join(config.BIN_DIR, "llama", "**", "llama-*"), recursive=True):
                if not config.IS_WIN and os.path.isfile(f) and "." not in os.path.basename(f): os.chmod(f, 0o755)
    if config.IS_WIN and not a.skip_poppler:
        print("[2/3] poppler (pdftotext)")
        if os.path.isdir(os.path.join(config.BIN_DIR, "poppler")) and config.POPPLER_BIN: print("  already installed")
        else:
            z = os.path.join(config.BIN_DIR, "poppler.zip"); download(POPPLER[0], z, POPPLER[1])
            extract(z, os.path.join(config.BIN_DIR, "poppler")); os.remove(z)
    elif not config.IS_WIN and not shutil.which("pdftotext"):
        print("[2/3] pdftotext is missing: install poppler-utils (for example: sudo apt install poppler-utils)")
    if a.models_dir:  # remember it for run / bench
        a.models_dir = os.path.abspath(a.models_dir); os.makedirs(a.models_dir, exist_ok=True)
        cfg = os.path.join(config.HERE, "config.env")
        lines = [l for l in (open(cfg, encoding="utf-8").read().splitlines() if os.path.isfile(cfg) else []) if not l.startswith("MODELS_DIR=")]
        open(cfg, "w", encoding="utf-8").write("\n".join(lines + [f"MODELS_DIR={a.models_dir}"]) + "\n"); print(f"  MODELS_DIR={a.models_dir} saved to config.env")
        config.MODELS_DIR = config.BIG_MODELS_DIR = a.models_dir; config.EMBED_MODEL = os.path.join(a.models_dir, "embed", os.path.basename(config.EMBED_MODEL))  # this run uses it too
    names = [] if a.tools_only else ["nomic-embed-text-v1.5", "Qwen3-4B-Instruct-2507"]
    names += [n.strip() for n in a.install.split(",") if n.strip()]
    print(f"[3/3] models: {', '.join(names) or 'none'}")
    for n in names:
        e = modelstore.entry(n)
        print(f"  {e['name']} ({e['size_bytes'] / 2**20:,.0f} MB) -> {modelstore.target_dir(e)}")
        ok, msg = modelstore.download(e)
        print("  " + msg)
    print("\nSetup finished. Next: run.ps1 (Windows) or ./run.sh (Linux). To compare models: bench_all.ps1")


if __name__ == "__main__":
    main()
