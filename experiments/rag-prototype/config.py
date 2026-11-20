"""Machine settings for the prototype. Everything comes from environment variables or config.env (KEY=VALUE lines,
next to this file); the defaults are folders next to the code, so a fresh download works without editing anything."""
import glob, os, shutil, subprocess, sys

for _s in (sys.stdout, sys.stderr):  # Windows consoles default to a legacy code page
    try: _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError): pass

HERE = os.path.dirname(os.path.abspath(__file__))
IS_WIN = os.name == "nt"
EXE = ".exe" if IS_WIN else ""

try:  # config.env never overrides a variable that is already set
    for _line in open(os.path.join(HERE, "config.env"), encoding="utf-8"):
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _v = _line.split("=", 1)
            os.environ.setdefault(_k.strip(), _v.strip().strip('"'))
except OSError:
    pass


def env(key, default=""):
    return os.environ.get(key) or default


def _find(name, root):
    hits = glob.glob(os.path.join(root, "**", name), recursive=True)
    return hits[0] if hits else None


BIN_DIR = env("BIN_DIR", os.path.join(HERE, "bin"))
LLAMA_SERVER = env("LLAMA_SERVER") or _find("llama-server" + EXE, BIN_DIR) or shutil.which("llama-server") or ""
LLAMA_BENCH = env("LLAMA_BENCH") or (os.path.join(os.path.dirname(LLAMA_SERVER), "llama-bench" + EXE) if LLAMA_SERVER else "")
POPPLER_BIN = env("POPPLER_BIN") or os.path.dirname(_find("pdftotext" + EXE, BIN_DIR) or "") or ""
PDFTOTEXT = os.path.join(POPPLER_BIN, "pdftotext" + EXE) if POPPLER_BIN else "pdftotext"

MODELS_DIR = env("MODELS_DIR", os.path.join(HERE, "models", "llm"))
MODEL_DIRS = [MODELS_DIR] + [d for d in env("MODELS_EXTRA_DIRS").split(os.pathsep) if d]  # extra folders for the model dropdown
EMBED_KIND = env("EMBED_KIND", "nomic")  # nomic | bge
EMBED_MODEL = env("EMBED_MODEL", os.path.join(env("MODELS_DIR") or os.path.join(HERE, "models"), "embed", "nomic-embed-text-v1.5.Q8_0.gguf"))  # beside the LLM files when MODELS_DIR is set

APP_PORT = int(env("APP_PORT", "8000"))
LLM_PORT = int(env("LLM_PORT", "8090"))
EMBED_PORT = int(env("EMBED_PORT", "8091"))

# cpu = no layers on the GPU; vulkan = offload everything. 12 threads is a CPU-only default, a GPU run needs few.
LLM_BACKEND = env("LLM_BACKEND", "vulkan" if IS_WIN else "cpu")
LLM_NGL = int(env("LLM_NGL", "0" if LLM_BACKEND == "cpu" else "99"))
LLM_THREADS = int(env("LLM_THREADS", str(min(12, os.cpu_count() or 4) if LLM_NGL == 0 else 4)))
BIG_MODELS_DIR = env("BIG_MODELS_DIR") or MODELS_DIR  # where the two optional 30B MoE files are downloaded (can be an HDD)
LLM_NO_MMAP = env("LLM_NO_MMAP") not in ("", "0")  # read the whole file into RAM at load (used for MoE models with experts in RAM, so a slow disk is not read during answers)
LLM_N_CPU_MOE = env("LLM_N_CPU_MOE")  # MoE models: keep the experts of the first N layers in system RAM (llama-server --n-cpu-moe); empty = off, 99 = all
LLM_MODEL = env("LLM_MODEL", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf")  # same default on GPU and CPU
LLM_SLOTS = int(env("LLM_SLOTS", "5" if LLM_NGL > 0 else "1"))  # context = 2048 x slots, as the Hub docs say; 5 learners at once on a GPU
LLM_BATCH = int(env("LLM_BATCH", "2048"))
LLM_UBATCH = int(env("LLM_UBATCH", "512"))
EMBED_THREADS = int(env("EMBED_THREADS", "2"))
EMBED_NGL = int(env("EMBED_NGL", "0"))  # set 99 to embed on the GPU (faster ingest of a long PDF)

# What the page starts with (the best settings found so far); see README "Defaults"
DEFAULT_RETRIEVER = env("DEFAULT_RETRIEVER", "hybrid")
DEFAULT_RERANK = env("DEFAULT_RERANK", "on")
DEFAULT_PROMPT = env("DEFAULT_PROMPT", "V5")
DEFAULT_THRESHOLD = env("DEFAULT_THRESHOLD")  # empty = the calibrated value in calibration.json
MAX_CHUNKS = int(env("MAX_CHUNKS", "3"))
LOGS = os.path.join(HERE, "logs")


def llm_args(model, port=None):
    return [LLAMA_SERVER, "-m", model, "-c", str(2048 * LLM_SLOTS), "-np", str(LLM_SLOTS), "-ngl", str(LLM_NGL),
            "-t", str(LLM_THREADS), "-b", str(LLM_BATCH), "-ub", str(LLM_UBATCH), "-lv", "4", "--host", "127.0.0.1", "--port", str(port or LLM_PORT)] + (["--n-cpu-moe", LLM_N_CPU_MOE] if LLM_N_CPU_MOE else []) + (["--load-mode", "none"] if LLM_NO_MMAP else [])


def embed_args(port=None):
    ctx = 2048 if EMBED_KIND == "nomic" else 512  # bge has absolute positions up to 512
    return [LLAMA_SERVER, "-m", EMBED_MODEL, "--embedding", "-c", str(ctx), "-b", str(ctx), "-ub", str(ctx), "-np", "1",
            "-ngl", str(EMBED_NGL), "-t", str(EMBED_THREADS), "--host", "127.0.0.1", "--port", str(port or EMBED_PORT)]


def popen_kwargs():
    """Detach the child from our console / session so it survives and can be signalled on its own."""
    if IS_WIN:
        return {"creationflags": 0x00000200 | 0x08000000}  # CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW
    return {"start_new_session": True}


def kill_tree(pid):
    if IS_WIN:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        import signal
        try: os.kill(pid, signal.SIGTERM)
        except OSError: pass


def pid_alive(pid):
    if IS_WIN:
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
        return str(pid) in out
    try: os.kill(pid, 0); return True
    except OSError: return False


def port_pid(port):
    """PID listening on a local port (netstat on Windows, ss on Linux); None when unknown."""
    import re
    try:
        if IS_WIN:
            out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
            for line in out.splitlines():
                p = line.split()
                if len(p) >= 5 and p[3] == "LISTENING" and p[1].endswith(":%d" % port): return int(p[4])
            return None
        out = subprocess.run(["ss", "-ltnpH", f"sport = :{port}"], capture_output=True, text=True).stdout
        m = re.search(r"pid=(\d+)", out)
        return int(m.group(1)) if m else None
    except (OSError, ValueError):
        return None
