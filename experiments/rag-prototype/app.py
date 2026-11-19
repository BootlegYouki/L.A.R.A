"""Stdlib web app for the RAG tutor prototype. No auth: same-Wi-Fi use only, bound explicitly to 0.0.0.0:8000."""
import json, os, re, urllib.error, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import config
import rag

SLOTS = threading.BoundedSemaphore(config.LLM_SLOTS)  # one chat per llama-server slot; more wait for a free one
ACTIVE = [0]  # chats in flight (a model switch is refused while any runs)
ACTIVE_LOCK = threading.Lock()
SAMPLE = os.path.join(rag.HERE, "sample", "lesson.pdf")
MODEL_DIRS = config.MODEL_DIRS
LOGS = config.LOGS
PORT = config.LLM_PORT
os.makedirs(LOGS, exist_ok=True)


def models():
    return {f: os.path.join(d, f) for d in MODEL_DIRS if os.path.isdir(d) for f in sorted(os.listdir(d)) if f.endswith(".gguf")}


PROC = None  # the llama-server this app started, so it can stop and reap it
SWITCHING = threading.Lock()
SWITCH_ERROR = None


def port_pid():
    return config.port_pid(PORT)


def port_free():
    import socket
    s = socket.socket()
    if not config.IS_WIN: s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)  # on Windows REUSEADDR would let a second bind succeed
    try: s.bind(("127.0.0.1", PORT)); return True
    except OSError: return False
    finally: s.close()


def wait_for(cond, secs):
    for _ in range(int(secs * 2)):
        if cond(): return True
        time.sleep(0.5)
    return cond()


def stop_llm():
    """Stops the LLM: our own child first, else whatever listens on the port, else the pid file. Waits until the port is free."""
    global PROC
    pids = []
    if PROC and PROC.poll() is None: pids.append(PROC.pid)
    elif port_pid(): pids.append(port_pid())
    else:
        try: pids.append(int(open(os.path.join(LOGS, "llm.pid"), encoding="utf-8").read()))
        except (OSError, ValueError): pass
    for pid in pids:
        config.kill_tree(pid)
        wait_for(lambda: not config.pid_alive(pid), 30)
        if config.pid_alive(pid):  # still there: force
            if config.IS_WIN: config.kill_tree(pid)
            else:
                import signal
                try: os.kill(pid, signal.SIGKILL)
                except OSError: pass
            wait_for(lambda: not config.pid_alive(pid), 10)
    return wait_for(port_free, 10)


def start_llm(path):
    global PROC
    PROC = subprocess.Popen(config.llm_args(path), stdout=open(os.path.join(LOGS, "llm.log"), "w"), stderr=subprocess.STDOUT, **config.popen_kwargs())
    threading.Thread(target=PROC.wait, daemon=True).start()  # reaps the child when it exits
    return PROC


def started_ok(p):
    """True once /health answers; False if the child exits or never answers."""
    for _ in range(75):
        if p.poll() is not None: return False
        if rag.LLM_UP():
            pid = port_pid()
            if pid: open(os.path.join(LOGS, "llm.pid"), "w", encoding="utf-8").write(str(pid))  # written from the real listener
            return True
        time.sleep(2)
    return False


def last_log_line():
    try: return [l for l in open(os.path.join(LOGS, "llm.log"), encoding="utf-8", errors="replace").read().splitlines() if l.strip()][-1][:200]
    except Exception: return "no log"


def switch_model(path):
    """One switch at a time; returns False if one is already running."""
    if not SWITCHING.acquire(blocking=False): return False
    global SWITCH_ERROR
    SWITCH_ERROR = None
    prev = models().get(rag.current_model() or "")

    def go():
        global SWITCH_ERROR
        try:
            if not stop_llm():
                SWITCH_ERROR = f"Could not load {os.path.basename(path)}: the old model did not stop"; return
            if started_ok(start_llm(path)): return
            SWITCH_ERROR = f"Could not load {os.path.basename(path)}: {last_log_line()}"
            if PROC.poll() is None: PROC.kill()
            if prev and wait_for(port_free, 10) and started_ok(start_llm(prev)): pass  # put the previous model back
        finally:
            SWITCHING.release()
    threading.Thread(target=go, daemon=True).start()
    return True


HTML = open(os.path.join(rag.HERE, "index.html"), encoding="utf-8").read()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send_json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_GET(self):
        if self.path == "/":
            b = HTML.encode(); self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        elif self.path == "/api/status":
            m = rag.status_info()
            self.send_json({"pages": m["pages"], "chunks": m["chunks"], "ingesting": m["ingesting"], "embedder": rag.EMBED_NAME, "threshold": rag.default_threshold(),
                            "progress": m["progress"], "ingest_error": m["ingest_error"], "defaults": {"retriever": config.DEFAULT_RETRIEVER, "rerank": config.DEFAULT_RERANK, "prompt": config.DEFAULT_PROMPT, "threshold": rag.default_threshold(), "teacher_view": True, "max_chunks": config.MAX_CHUNKS, "slots": config.LLM_SLOTS}, "llm_up": rag.LLM_UP(), "model": rag.current_model(), "switch_error": SWITCH_ERROR, "switching": SWITCHING.locked(), "retrievers": rag.RETRIEVERS})
        elif self.path == "/api/models":
            self.send_json({"models": [{"name": n, "size_mb": os.path.getsize(p) // 2**20} for n, p in models().items()], "loaded": rag.current_model()})
        else:
            self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        try:
            if self.path.startswith("/api/ingest"):
                data = open(SAMPLE, "rb").read() if "sample=1" in self.path else self.body()
                if not data.startswith(b"%PDF"): return self.send_json({"error": "That file is not a PDF."}, 400)
                if rag.INGESTING: return self.send_json({"error": "A lesson is already being loaded."}, 409)
                threading.Thread(target=lambda: _bg_ingest(data), daemon=True).start()  # a 300-page PDF takes minutes: never block the request
                self.send_json({"started": True}, 202)
            elif self.path == "/api/model":
                path = models().get(json.loads(self.body()).get("name"))  # only names from the list, never client paths
                if not path: return self.send_json({"error": "Unknown model."}, 400)
                if ACTIVE[0]: return self.send_json({"error": "A reply is in progress; try again in a moment."}, 409)
                if not switch_model(path): return self.send_json({"error": "A model switch is already in progress."}, 409)
                self.send_json({"loading": os.path.basename(path)})
            elif self.path == "/api/chat":
                self.chat(json.loads(self.body()))
            else:
                self.send_json({"error": "not found"}, 404)
        except Exception as e:  # a bad request must never kill the server
            try: self.send_json({"error": f"{type(e).__name__}: {e}"}, 500)
            except Exception: pass

    def chat(self, req):
        if rag.INGESTING: return self.send_json({"error": "The lesson is being loaded, try again in a minute."}, 503)
        if not rag.meta().get("pages"): return self.send_json({"error": "Load a lesson first."}, 400)
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Cache-Control", "no-cache"); self.end_headers()
        emit = lambda ev, d: (self.wfile.write(f"event: {ev}\ndata: {json.dumps(d)}\n\n".encode()), self.wfile.flush())
        if SWITCHING.locked() or not rag.LLM_UP():
            return emit("error", {"error": "The model is loading, try again in a few seconds."})
        acquired = False
        try:
            if not SLOTS.acquire(blocking=False):
                emit("stage", {"text": "Waiting for a free slot..."}); SLOTS.acquire()
            acquired = True
            with ACTIVE_LOCK: ACTIVE[0] += 1
            emit("stage", {"text": "Searching the lesson..."})
            b = rag.build(req["message"], req.get("history", []), req.get("retriever", config.DEFAULT_RETRIEVER), float(req.get("threshold", rag.default_threshold())), req.get("variant", config.DEFAULT_PROMPT), req.get("rerank"))
            emit("sources", {k: v for k, v in b.items() if k != "messages"})
            emit("stage", {"text": "Waiting for the model..." if not b["forced_reply"] else ""})
            if b["forced_reply"]:  # Hub-side language check: the model is not called
                emit("token", b["forced_reply"]); emit("done", {"ttft_s": 0, "tokens": 0, "tok_per_s": 0, "prompt_eval_ms": 0, "cached_tokens": 0})
            else:
                for k, v in rag.guarded_stream(b):
                    emit("token" if k == "token" else "done", {x: y for x, y in v.items() if x != "raw"} if k == "done" else v)
        except Exception as e:
            down = isinstance(e, (ConnectionError, urllib.error.URLError))
            emit("error", {"error": str(e) if isinstance(e, rag.EmbedBusy) else "The model is loading, try again in a few seconds." if down else f"{type(e).__name__}: {e}"})
        finally:
            if acquired:
                with ACTIVE_LOCK: ACTIVE[0] -= 1
                SLOTS.release()


def _bg_ingest(data):
    try: rag.ingest(data)
    except Exception as e: print("ingest failed:", e, flush=True)


def autoload():
    """A fresh start or a deleted rag.db must not need a button click; an already ingested lesson is left alone."""
    if os.path.exists(rag.DB_PATH) and rag.load(): return
    for _ in range(300):  # the model servers are starting in parallel: wait up to 10 min
        if rag.LLM_UP() and rag.embed_up(): break
        time.sleep(2)
    print("auto-loading sample lesson:", rag.ingest(open(SAMPLE, "rb").read()), flush=True)


if __name__ == "__main__":
    autoload()
    ThreadingHTTPServer(("0.0.0.0", config.APP_PORT), H).serve_forever()  # no auth: same LAN only
