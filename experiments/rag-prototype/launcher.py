"""start / stop / status for the three processes: embedder, LLM, web app. Same code on Windows and Linux.
    python launcher.py start [--model FILE_OR_NAME]     python launcher.py stop     python launcher.py status"""
import argparse, glob, os, re, subprocess, sys, time, urllib.request
import config

LOGS = config.LOGS
PREFER = ["Qwen3-4B-Instruct-2507"]  # LLM_MODEL (default the 4B) is used first; this is only the fallback


def up(port):
    try: urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2).read(); return True
    except Exception: return False


def lan_ip():
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try: s.connect(("10.255.255.255", 1)); return s.getsockname()[0]  # no packet is sent; picks the LAN interface
    except OSError: return "127.0.0.1"
    finally: s.close()


def pick_model(arg):
    arg = arg or config.LLM_MODEL
    if arg:
        for c in (arg, os.path.join(config.MODELS_DIR, arg)):
            if os.path.isfile(c): return c
        if arg != config.LLM_MODEL: sys.exit(f"Model not found: {arg}")
    files = sorted(glob.glob(os.path.join(config.MODELS_DIR, "*.gguf")))
    for p in PREFER:
        for f in files:
            if p.lower() in os.path.basename(f).lower(): return f
    if files: return files[0]
    sys.exit(f"No .gguf model in {config.MODELS_DIR}. Run setup first.")


def spawn(name, args, extra_env=None):
    log = open(os.path.join(LOGS, name + ".log"), "w", encoding="utf-8")
    env = dict(os.environ, PYTHONUTF8="1", **(extra_env or {}))
    p = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, cwd=config.HERE, env=env, **config.popen_kwargs())
    open(os.path.join(LOGS, name + ".pid"), "w").write(str(p.pid))
    return p


def wait_up(port, p, name, secs):
    for _ in range(secs):
        if p.poll() is not None: sys.exit(f"{name} stopped at once (exit {p.returncode}). Last log lines:\n" + tail(name))
        if up(port): return
        time.sleep(1)
    sys.exit(f"{name} did not answer within {secs} s. Last log lines:\n" + tail(name))


def tail(name, n=8):
    try: return "\n".join(open(os.path.join(LOGS, name + ".log"), encoding="utf-8", errors="replace").read().splitlines()[-n:])
    except OSError: return "(no log)"


def gpu_report():
    """What the LLM log says about the device; shouts if a GPU run silently fell back to CPU."""
    log = open(os.path.join(LOGS, "llm.log"), encoding="utf-8", errors="replace").read()
    dev = re.findall(r"ggml_vulkan: \d+ = (.+)", log)
    off = re.search(r"offloaded (\d+)/(\d+) layers to GPU", log)
    print("Vulkan devices seen:", "; ".join(d.strip() for d in dev) or "none")
    if off: print(f"Layers on GPU: {off.group(1)} of {off.group(2)}")
    if config.LLM_NGL > 0 and (not off or int(off.group(1)) == 0):
        print("\n" + "!" * 70 + "\n!!  WARNING: the model is running on the CPU, not the GPU.  !!\n"
              "!!  Update the GPU driver, check that this is a Vulkan build, see README troubleshooting.  !!\n" + "!" * 70)


def start(model_arg):
    os.makedirs(LOGS, exist_ok=True)
    if not config.LLAMA_SERVER or not os.path.isfile(config.LLAMA_SERVER): sys.exit("llama-server not found. Run setup first (or set LLAMA_SERVER).")
    if not os.path.isfile(config.EMBED_MODEL): sys.exit(f"Embedder model missing: {config.EMBED_MODEL}. Run setup first.")
    model = pick_model(model_arg)
    for port, what in ((config.LLM_PORT, "LLM"), (config.EMBED_PORT, "embedder"), (config.APP_PORT, "web app")):
        if config.port_pid(port): sys.exit(f"Port {port} ({what}) is already in use. Run stop first, or change the port in config.env.")
    print(f"Model: {os.path.basename(model)}   backend: {config.LLM_BACKEND}   -ngl {config.LLM_NGL}   threads {config.LLM_THREADS}   slots {config.LLM_SLOTS}")
    emb = spawn("embed", config.embed_args())
    t_llm = time.time()
    llm = spawn("llm", config.llm_args(model))
    wait_up(config.EMBED_PORT, emb, "embed", 120)
    print("Embedder is up. Loading the model (a minute or two)...")
    wait_up(config.LLM_PORT, llm, "llm", 1800)  # a 17 GB file on a hard disk can need many minutes
    open(os.path.join(LOGS, "load_seconds.txt"), "w").write(str(round(time.time() - t_llm, 1)))  # process start to /health ok
    gpu_report()
    spawn("app", [sys.executable, os.path.join(config.HERE, "app.py")])
    for _ in range(30):
        time.sleep(1)
        try: urllib.request.urlopen(f"http://127.0.0.1:{config.APP_PORT}/api/status", timeout=2).read(); break
        except Exception: pass
    print(f"\nOpen  http://{lan_ip()}:{config.APP_PORT}   (this PC: http://localhost:{config.APP_PORT})")
    print("The first start reads the sample lesson (1 to 3 minutes); until then the page does not answer.")
    print("Windows may ask to allow Python / llama-server through the firewall: allow it on Private networks.")


def stop():
    had_llm = False
    for name in ("app", "embed", "llm"):
        f = os.path.join(LOGS, name + ".pid")
        try: pid = int(open(f).read())
        except (OSError, ValueError): continue
        had_llm = had_llm or name == "llm"
        if config.pid_alive(pid): config.kill_tree(pid)
        try: os.remove(f)
        except OSError: pass
    pid = config.port_pid(config.LLM_PORT) if had_llm else None  # a model switch in the page replaced llm.pid: catch our own leftover
    if pid: config.kill_tree(pid)
    print("stopped")


def status():
    for name, port in (("embedder", config.EMBED_PORT), ("llm", config.LLM_PORT)):
        print(f"{name}: {'up' if up(port) else 'down'} (port {port})")
    try: urllib.request.urlopen(f"http://127.0.0.1:{config.APP_PORT}/api/status", timeout=2).read(); print("web app: up")
    except Exception: print("web app: down")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("cmd", choices=["start", "stop", "status"]); ap.add_argument("--model")
    a = ap.parse_args()
    {"start": lambda: start(a.model), "stop": stop, "status": status}[a.cmd]()
