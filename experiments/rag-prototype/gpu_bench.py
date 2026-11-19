"""Benchmarks and compares models on this machine. Standard library only. Safe to stop and run again (results/state.json).
    python gpu_bench.py                 the 4B baseline only (about 30 to 45 minutes)
    python gpu_bench.py --all           every .gguf in the models folder, one at a time (hours; see README)
    python gpu_bench.py --all --pdf2 your.pdf --key2 your_key.json   also the second PDF for models that are fast enough
Per model: load test (VRAM, layers on GPU), llama-bench pp512/pp1024/tg128 x3, concurrency at 1/3/5 users, quality run (sample key, prompt V5).
Output: results/MODEL_COMPARISON.md and results/<computer>-<date>.md"""
import argparse, datetime, glob, json, os, platform, re, statistics, subprocess, sys, time
import config
import modelstore

ap = argparse.ArgumentParser()
ap.add_argument("--all", action="store_true", help="every model in models.json, ONE AT A TIME: download, benchmark, delete")
ap.add_argument("--resume", action="store_true", help="skip models already finished in results/state.json"); ap.add_argument("--keep", action="store_true", help="do not delete the downloaded files")
ap.add_argument("--install", default="", help="at the end, download and keep this model (the winner)"); ap.add_argument("--yes", action="store_true", help="do not ask before starting")
ap.add_argument("--pdf2", default=""); ap.add_argument("--key2", default="")
ap.add_argument("--only", default="", help="comma list of model names from models.json")
a = ap.parse_args()

R = os.path.join(config.HERE, "results"); RAW = os.path.join(R, "raw"); RUNS = os.path.join(R, "raw", "runs")
os.makedirs(RUNS, exist_ok=True)
NAME = re.sub(r"[^A-Za-z0-9_-]", "", os.environ.get("COMPUTERNAME") or platform.node() or "pc")[:24] or "pc"
DAY = datetime.date.today().isoformat()
STATE_FILE = os.path.join(R, "state.json")
STATE = {"rows": {}}
if os.path.isfile(STATE_FILE):
    try: STATE = json.load(open(STATE_FILE, encoding="utf-8"))
    except ValueError: pass
STATE.setdefault("rows", {})
SAMPLE_KEY = os.path.join(config.HERE, "sample", "answer_key.json")
ENV0 = dict(os.environ, PYTHONUTF8="1")
FAST_P95 = 10.0
QUICK = bool(os.environ.get("BENCH_QUICK"))  # smoke test of the script itself: tiny runs


def save(): json.dump(STATE, open(STATE_FILE, "w", encoding="utf-8"), indent=1)


def run(cmd, timeout, raw_name, env=None):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, cwd=config.HERE, env=env or ENV0)
        out, rc = (p.stdout or "") + "\n--- stderr ---\n" + (p.stderr or ""), p.returncode
    except subprocess.TimeoutExpired as e:
        out, rc = f"TIMEOUT after {timeout}s\n{e.stdout or ''}", -1
    open(os.path.join(RAW, re.sub(r"[^\w.-]", "_", raw_name) + ".txt"), "w", encoding="utf-8").write(out)
    return rc, out


def ps(cmd):
    try: return subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True, timeout=60).stdout.strip()
    except (OSError, subprocess.SubprocessError): return ""


def hardware():
    h = {"os": platform.platform(), "build": platform.version(), "python": platform.python_version(), "cpu_threads": os.cpu_count()}
    if config.IS_WIN:
        h["cpu"] = ps("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name")
        h["ram_gb"] = ps("[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)")
        h["ram_speed"] = ps("Get-CimInstance Win32_PhysicalMemory | ForEach-Object { ([string]($_.Capacity/1GB)) + ' GB at ' + $_.ConfiguredClockSpeed + ' MT/s' }").replace("\r", "").replace("\n", "; ")
        h["gpus"] = ps("Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name + ' | driver ' + $_.DriverVersion }").replace("\r", "").split("\n")
    else:
        try: h["cpu"] = re.search(r"model name\s*:\s*(.+)", open("/proc/cpuinfo", encoding="utf-8").read()).group(1)
        except (OSError, AttributeError): h["cpu"] = platform.processor()
        try: h["ram_gb"] = round(int(re.search(r"MemTotal:\s+(\d+)", open("/proc/meminfo", encoding="utf-8").read()).group(1)) / 2**20, 1)
        except (OSError, AttributeError): h["ram_gb"] = "?"
        h["ram_speed"] = "not available without root (the MoE-on-CPU speed depends on it)"
        try: h["gpus"] = [l for l in subprocess.run(["lspci"], capture_output=True, text=True).stdout.splitlines() if "VGA" in l or "3D" in l]
        except OSError: h["gpus"] = []
    return h


def free_ram_gb():
    try:
        if config.IS_WIN: return round(float(ps("(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory")) / 2**20, 1)
        return round(int(re.search(r"MemAvailable:\s+(\d+)", open("/proc/meminfo", encoding="utf-8").read()).group(1)) / 2**20, 1)
    except (ValueError, OSError, AttributeError): return None


DRIVES = {}


def drive_type(folder):
    """SSD or HDD for the drive that holds the models; asks when Windows does not say (only if someone is at the keyboard)."""
    t = ""
    if config.IS_WIN:
        drv = os.path.splitdrive(os.path.abspath(folder))[0].rstrip(":")
        t = ps(f"(Get-Partition -DriveLetter {drv} | Get-Disk | Get-PhysicalDisk).MediaType") if drv else ""
    if t.strip() in ("", "Unspecified") and sys.stdin and sys.stdin.isatty():
        try: t = input(f"Is the folder {folder} on an SSD or an HDD? ").strip() or "unknown"
        except EOFError: t = "unknown"
    return t.strip() or "unknown (Linux: see lsblk -d -o NAME,ROTA)"


class Paging:
    """Samples memory paging while a MoE model runs: Windows Pages/sec (typeperf), Linux swap in/out per second."""
    def __init__(self): self.p = None; self.f = os.path.join(RAW, "paging.csv"); self.v0 = None; self.t0 = None

    def start(self):
        self.t0 = time.time()
        if config.IS_WIN:
            self.p = subprocess.Popen(["typeperf", r"\Memory\Pages/sec", "-si", "5", "-o", self.f, "-y"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            try: self.v0 = sum(int(l.split()[1]) for l in open("/proc/vmstat", encoding="utf-8") if l.startswith(("pswpin", "pswpout")))
            except OSError: self.v0 = None

    def stop(self):
        if config.IS_WIN and self.p:
            self.p.terminate(); time.sleep(1)
            try:
                v = [float(x.split(",")[1].strip('"')) for x in open(self.f, encoding="utf-8", errors="replace").read().splitlines()[1:] if x.count(",") and x.split(",")[1].strip('"').replace(".", "", 1).isdigit()]
                return {"avg_pages_per_s": round(sum(v) / len(v), 1), "max_pages_per_s": round(max(v), 1), "samples": len(v)} if v else {"note": "no samples"}
            except OSError: return {"note": "no samples"}
        if self.v0 is not None:
            v1 = sum(int(l.split()[1]) for l in open("/proc/vmstat", encoding="utf-8") if l.startswith(("pswpin", "pswpout")))
            return {"swap_pages_per_s": round((v1 - self.v0) / max(time.time() - self.t0, 1), 1)}
        return {"note": "not measured"}


def moe_variants(name):
    """Big MoE models: experts of N layers stay in system RAM. 5 GB of experts in VRAM is about 14 layers of the original 30B, 27 of the pruned one."""
    n = name.lower()
    if "reap" in n: return [("all experts on CPU (--n-cpu-moe 99, --no-mmap)", "99"), ("experts of 21 layers on CPU, rest in VRAM (--n-cpu-moe 21, --no-mmap)", "21")]
    if "qwen3-30b-a3b" in n: return [("all experts on CPU (--n-cpu-moe 99, --no-mmap)", "99"), ("experts of 34 layers on CPU, rest in VRAM (--n-cpu-moe 34, --no-mmap)", "34")]
    return [("", "")]


def log_info():
    try: log = open(os.path.join(config.LOGS, "llm.log"), encoding="utf-8", errors="replace").read()
    except OSError: return {}, ""
    out = {"device": "; ".join(d.strip() for d in re.findall(r"ggml_vulkan: \d+ = (.+)", log))}
    for kind in ("model", "KV", "compute"):
        m = re.findall(r"Vulkan\d*\s+%s buffer size\s*=\s*([\d.]+)\s*MiB" % kind, log)
        if m: out[kind + "_mib"] = round(sum(float(x) for x in m))
    off = re.search(r"offloaded (\d+)/(\d+) layers to GPU", log)
    if off: out["layers"] = f"{off.group(1)}/{off.group(2)}"
    err = [l.strip() for l in log.splitlines() if re.search(r"error|failed|unknown model architecture|out of memory|unsupported|abort", l, re.I)]
    return out, (err[-1][:220] if err else (log.strip().splitlines() or ["no log"])[-1][:220])


def stop(): run([sys.executable, "launcher.py", "stop"], 120, "stop")


def start(model, moe, slots):
    env = dict(ENV0, LLM_SLOTS=str(slots)); env.pop("LLM_N_CPU_MOE", None); env.pop("LLM_NO_MMAP", None)
    if moe: env["LLM_N_CPU_MOE"] = moe; env["LLM_NO_MMAP"] = "1"  # whole file into RAM at load: no disk reads while answering
    stop()
    rc, out = run([sys.executable, "launcher.py", "start", "--model", model], 1500, "start-" + os.path.basename(model) + f"-{moe}-{slots}", env)
    return rc == 0, env


def bench(model, ngl, moe, label):
    th = str(min(12, os.cpu_count() or 4)) if ngl == 0 else "4"
    extra = (["-ncmoe", moe] if moe and ngl else []) + (["-mmp", "0"] if moe else [])
    cmd = [config.LLAMA_BENCH, "-m", model, "-ngl", str(ngl), "-p", "512,1024", "-n", "128", "-r", "3", "-t", th, "-o", "json"] + extra
    run([config.LLAMA_BENCH, "-m", model, "-ngl", str(ngl), "-p", "64", "-n", "16", "-r", "1", "-t", th, "-o", "json"] + extra, 3600, "warmup-" + label)  # discarded: reads the file from a slow disk once
    rc, out = run(cmd, 2400, "bench-" + label)
    try:
        j = out.split("--- stderr ---")[0].strip(); rows = json.loads(j[j.index("["):j.rindex("]") + 1])
    except ValueError: rows = []
    pp = {r.get("n_prompt"): r.get("avg_ts") for r in rows if r.get("n_prompt") and not r.get("n_gen")}
    tg = next((r.get("avg_ts") for r in rows if r.get("n_gen")), None)
    return {"rc": rc, "pp512": pp.get(512), "pp1024": pp.get(1024), "tg128": tg, "cmd": " ".join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd))}


def quality(env, key, outdir, prompts, extra_env=None):
    os.makedirs(outdir, exist_ok=True)
    cmd = [sys.executable, "rag_eval.py", "--generate", "4" if QUICK else "all", "--prompt", prompts, "--outdir", outdir] + (["--light"] if QUICK else []) + (["--key", key] if key else [])
    run(cmd, 6 * 3600, "quality-" + os.path.basename(outdir), dict(env, **(extra_env or {})))
    res = {}
    for p in prompts.split(","):
        try: rows = json.load(open(os.path.join(outdir, f"gen_{p}.json"), encoding="utf-8"))
        except (OSError, ValueError): continue
        q = [r for r in rows if r["id"].startswith("Q")]; nt = [r for r in rows if not r["id"].startswith("T")]
        pc = lambda xs, n: round(100 * sum(1 for x in xs if x) / max(n, 1), 1)
        nc = lambda r: bool(re.search(r"does not cover|doesn't cover|not covered|cannot help|can't help", r["reply"].lower()))
        res[p] = {"n": len(rows), "leak": pc([r["flags"].get("LEAK") for r in q], len(q)), "refusal": pc([r["flags"].get("REFUSAL_PHRASE") for r in nt], len(nt)),
                  "bad_cite": pc([r["flags"].get("BAD_CITE") for r in rows], len(rows)), "empty": pc([r["flags"].get("EMPTY") for r in rows], len(rows)),
                  "right_page": pc([r["flags"].get("POINTS") for r in q], len(q)), "wrong_nc": pc([nc(r) and r["key_page"] in r["pages_sent"] for r in q], len(q)),
                  "good": [(r["question"], r["reply"]) for r in q if r["flags"].get("POINTS") and not r["flags"].get("LEAK") and not r["flags"].get("REFUSAL_PHRASE")][:5],
                  "bad": [(r["question"], r["reply"], [k for k in ("LEAK", "REFUSAL_PHRASE", "BAD_CITE", "EMPTY") if r["flags"].get(k)] + (["WRONG_NOT_COVERED"] if nc(r) and r["key_page"] in r["pages_sent"] else []))
                         for r in q if r["flags"].get("LEAK") or r["flags"].get("REFUSAL_PHRASE") or r["flags"].get("BAD_CITE") or r["flags"].get("EMPTY") or (nc(r) and r["key_page"] in r["pages_sent"])][:5],
                  "ttft_median": statistics.median([r["ttft"] for r in rows if r.get("ttft")] or [0])}
    return res


def do_model(path, label, moe):
    name = os.path.basename(path); key = f"{name}|{label}"
    drive = DRIVES.setdefault(os.path.dirname(path), drive_type(os.path.dirname(path)))
    row = STATE["rows"].setdefault(key, {"model": name, "label": label, "size_gb": round(os.path.getsize(path) / 2**30, 1), "moe": moe})
    row["drive"] = drive
    if row.get("done"): print("  skip (done):", key); return
    print(f"\n== {key}", flush=True)
    row["free_ram_gb_before"] = free_ram_gb()
    pg = Paging() if moe else None
    if pg: pg.start()
    if "load" not in row:
        for slots in (5, 3, 1):
            ok, env = start(path, moe, slots)
            info, err = log_info()
            try: secs = float(open(os.path.join(config.LOGS, "load_seconds.txt"), encoding="utf-8").read())
            except (OSError, ValueError): secs = None
            if ok: row["load"] = {"ok": True, "slots": slots, "load_seconds": secs, **info, "fell_back_to_cpu": config.LLM_NGL > 0 and info.get("layers", "0/").split("/")[0] in ("", "0")}; row["env"] = {"slots": slots, "ngl": config.LLM_NGL, "threads": config.LLM_THREADS, "n_cpu_moe": moe}; break
            row["load"] = {"ok": False, "slots": slots, "error": err, **info}
            if not re.search(r"out of memory|failed to allocate|OutOfDeviceMemory|bad_alloc|vk::", err, re.I): break  # not a memory problem: fewer slots will not help
        save()
    ld = row["load"]
    if not ld["ok"]:
        if pg: pg.stop()
        stop(); row["done"] = True; save(); print("  did not load:", ld.get("error")); return
    if "conc" not in row:
        jp = os.path.join(RUNS, re.sub(r"\W", "_", key) + "-conc.json")
        run([sys.executable, "concurrency_test.py", "--warmup", "--users", "1,3,5", "--requests", "1" if QUICK else "4", "--json", jp], 5400, "conc-" + key, dict(ENV0))
        try: row["conc"] = json.load(open(jp, encoding="utf-8"))
        except (OSError, ValueError): row["conc"] = []
        save()
    c5 = next((c for c in row["conc"] if c["users"] == 5), None)
    row["fast"] = bool(c5) and c5["errors"] == 0 and c5["p95"] <= FAST_P95
    if "q" not in row:
        env = dict(ENV0, LLM_SLOTS=str(ld["slots"]))
        row["q"] = quality(env, "", os.path.join(RUNS, re.sub(r"\W", "_", key) + "-sample"), "V5"); save()
    if a.pdf2 and a.key2 and row["fast"] and "q2" not in row:
        db2 = os.path.join(config.HERE, "rag_second.db")
        if not os.path.isfile(db2): run([sys.executable, "rag_ingest.py", a.pdf2, db2], 7200, "ingest-second")
        row["q2"] = quality(dict(ENV0, RAG_DB=db2, KEY_FILE=a.key2), a.key2, os.path.join(RUNS, re.sub(r"\W", "_", key) + "-second"), "V2,V4,V5"); save()
    if pg: row["paging"] = pg.stop()
    stop()
    if "bench" not in row:
        ngl = config.LLM_NGL or 99
        row["bench"] = {"gpu": bench(path, ngl, moe, key + "-gpu")}
        if row["size_gb"] <= 6 and not moe: row["bench"]["cpu"] = bench(path, 0, "", key + "-cpu")  # baseline for models up to ~8B
        save()
    row["done"] = True; save()


def fmt(v, d=1): return "-" if v is None else f"{v:.{d}f}"


def quality_score(q): return q["right_page"] - q["leak"] - q["refusal"] - q["bad_cite"] - q["empty"] - q["wrong_nc"] if q else None


def report(hw):
    rows = list(STATE["rows"].values())
    L = ["# Model comparison", "", f"Machine {NAME}, {DAY}. Quality = prompt V5, hybrid, rerank on, MAX_CHUNKS=3, sample lesson (40 key questions + extras). Speed bar: p95 first token <= 10 s with 5 learners at once and no errors.", "",
         "| model | size GB | load OK | load time s | VRAM GB | pp512 t/s | tg128 t/s | first token p95 @5 (s) | per-stream t/s @5 | LEAK % | REFUSAL % | BAD_CITE % | EMPTY % | right page % | wrong 'not covered' % | verdict |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    ranked = []
    for r in rows:
        ld = r.get("load", {}); q = (r.get("q") or {}).get("V5"); c5 = next((c for c in r.get("conc", []) if c["users"] == 5), None); g = r.get("bench", {}).get("gpu", {})
        vram = (ld.get("model_mib", 0) + ld.get("KV_mib", 0) + ld.get("compute_mib", 0)) / 1024 if ld.get("model_mib") else None
        if r.get("skipped"): verdict = "skipped (" + ("not enough space" if "space" in str(ld.get("error")) else "download failed") + ")"
        elif not ld.get("ok"): verdict = "did not load"
        else:
            good = q and q["leak"] <= 10 and q["right_page"] >= 80 and q["wrong_nc"] <= 10
            verdict = "fast + good" if r.get("fast") and good else "fast but weak" if r.get("fast") else "smart but slow" if good else "slow and weak"
            if ld.get("fell_back_to_cpu"): verdict += " (ran on CPU!)"
        r["verdict"] = verdict
        name = r["model"].replace(".gguf", "") + (f" [{r['label']}]" if r["label"] else "")
        L.append(f"| {name} | {r['size_gb']} | {'yes' if ld.get('ok') else 'NO: ' + str(ld.get('error', ''))[:80]} | {fmt(ld.get('load_seconds'), 0)} | {fmt(vram)} | {fmt(g.get('pp512'), 0)} | {fmt(g.get('tg128'))} | {fmt(c5 and c5['p95'])} | {fmt(c5 and c5['tps_min'])} | "
                 + " | ".join(fmt(q and q[k]) for k in ("leak", "refusal", "bad_cite", "empty", "right_page", "wrong_nc")) + f" | {verdict} |")
        if ld.get("ok") and q: ranked.append((r, quality_score(q), c5["p95"] if c5 else 99))
    def best(lst, key): return max(lst, key=key)[0]["model"] if lst else "n/a"
    fastlist = [x for x in ranked if x[0].get("fast")]
    L += ["", "## Ranking", "", "Quality score = right page % - LEAK % - REFUSAL % - BAD_CITE % - EMPTY % - wrong 'not covered' %. Balance = quality x min(1, 10 / p95 first token at 5 users).", "",
          f"- **(a) best for 5 learners at once** (meets the speed bar, highest quality): {best(fastlist, lambda x: x[1])}",
          f"- **(b) best answers regardless of speed:** {best(ranked, lambda x: x[1])}",
          f"- **(c) best balance:** {best(ranked, lambda x: x[1] * min(1, 10 / max(x[2], 0.1)) if x[1] > 0 else x[1])}", "",
          "## Machine", "", f"- OS: {hw['os']} (build {hw['build']})", f"- CPU: {hw.get('cpu')} ({hw['cpu_threads']} threads)", f"- Models were stored on: {'; '.join(sorted({str(r.get('drive')) for r in STATE['rows'].values()}))} (load times depend on this; every model got one discarded warm-up run, so speeds do not include disk reads)", f"- RAM: {hw.get('ram_gb')} GB, {hw.get('ram_speed')}. For MoE models with experts in system RAM the speed depends heavily on this CPU and RAM bandwidth.",
          *[f"- GPU: {g}" for g in hw.get("gpus", [])], f"- llama.cpp: {os.path.basename(os.path.dirname(config.LLAMA_SERVER))}", "", "## Exact settings per model", ""]
    for r in rows:
        ld, e = r.get("load", {}), r.get("env", {})
        L.append(f"- {r['model']} {r['label']}: slots {ld.get('slots')}, context {2048 * (ld.get('slots') or 1)}, -ngl {e.get('ngl')}, threads {e.get('threads')}, --n-cpu-moe {e.get('n_cpu_moe') or '-'}; device: {ld.get('device') or '?'}; layers on GPU: {ld.get('layers') or '?'}; VRAM MiB model/KV/compute: {ld.get('model_mib')}/{ld.get('KV_mib')}/{ld.get('compute_mib')}"
                 + (f"; llama-bench: {r.get('bench', {}).get('gpu', {}).get('cmd')}" if r.get("bench") else ""))
    L += ["", "## Free RAM before each model and paging during the MoE runs", ""]
    for r in rows: L.append(f"- {r['model']} {r['label']}: free RAM before {r.get('free_ram_gb_before')} GB" + (f"; paging: {r['paging']}" if r.get("paging") else ""))
    L += ["", "## Concurrency (first token p50/p95 s, per-stream tok/s min)", "", "| model | learners | p50 | p95 | tok/s min | errors |", "|---|---|---|---|---|---|"]
    for r in rows:
        for c in r.get("conc", []): L.append(f"| {r['model']} {r['label']} | {c['users']} | {fmt(c['p50'])} | {fmt(c['p95'])} | {fmt(c['tps_min'])} | {c['errors']} |")
    L += ["", "## CPU baseline (llama-bench, -ngl 0)", "", "| model | pp512 | tg128 |", "|---|---|---|"]
    for r in rows:
        cpu = r.get("bench", {}).get("cpu")
        if cpu: L.append(f"| {r['model']} | {fmt(cpu.get('pp512'), 0)} | {fmt(cpu.get('tg128'))} |")
    L += ["", "## Second PDF (models that met the speed bar; prompts V2, V4, V5)", ""]
    for r in rows:
        for p, q in (r.get("q2") or {}).items(): L.append(f"- {r['model']} {p}: right page {q['right_page']}%, LEAK {q['leak']}%, REFUSAL {q['refusal']}%, BAD_CITE {q['bad_cite']}%, EMPTY {q['empty']}%, wrong not-covered {q['wrong_nc']}%")
    L += ["", "## Real replies (promising models: loaded and right page >= 60%)", ""]
    for r in rows:
        q = (r.get("q") or {}).get("V5")
        if not (q and q["right_page"] >= 60): continue
        L += [f"### {r['model']} {r['label']}", "", "Good:", *[f"- Q: {x[0]}\n  A: {x[1][:300]!r}" for x in q["good"]], "", "Bad:", *[f"- Q: {x[0]} [{', '.join(x[2])}]\n  A: {x[1][:300]!r}" for x in q["bad"]], ""]
    text = "\n".join(L) + "\n"
    open(os.path.join(R, "MODEL_COMPARISON.md"), "w", encoding="utf-8").write(text)
    summary = ["", "## Paste this back (10 lines)", "", "```", f"{NAME} {DAY} | {hw.get('cpu')} | {hw.get('ram_gb')} GB | " + "; ".join(hw.get("gpus", []))[:100],
               *[f"{r['model'][:34]} {r['label'][:18]}: {r['verdict']}; p95@5 {fmt(next((c['p95'] for c in r.get('conc', []) if c['users'] == 5), None))}s" for r in rows[:7]], "```"]
    open(os.path.join(R, f"{NAME}-{DAY}.md"), "w", encoding="utf-8").write(text + "\n".join(summary) + "\n")
    print("\n".join(L[:len(rows) + 6] + summary)); print(f"\nWritten: {os.path.join(R, 'MODEL_COMPARISON.md')}\nSend that file and results/{NAME}-{DAY}.md back (and results/raw if something failed).")


def main():
    if not config.LLAMA_BENCH or not os.path.isfile(config.LLAMA_BENCH): sys.exit("llama-bench not found. Run setup first.")
    hw = hardware(); STATE["hw"] = hw; save()
    ents = [e for e in modelstore.MANIFEST if not e["name"].startswith("nomic")]
    if a.only: ents = [e for e in ents if any(s.strip().lower() in e["name"].lower() for s in a.only.split(","))]
    elif not a.all: ents = [e for e in ents if e["role"] == "baseline"]
    ents.sort(key=lambda e: e.get("optional", False))  # manifest order, the optional MoE files last
    need = [e for e in ents if not modelstore.have(e)]
    total = sum(e["size_bytes"] for e in need) / 2**30
    big = max((e["size_bytes"] for e in ents), default=0) / 2**30
    print("GPU(s):", hw.get("gpus"))
    print(f"Models: {', '.join(e['name'] for e in ents)}\nDownload total: about {total:.1f} GB, one model at a time. Peak disk need: {big + 0.2 + 1:.1f} GB (the largest single model + embedder + 1 GB) on {config.MODELS_DIR}"
          + (f" (the optional 30B files on {config.BIG_MODELS_DIR})" if config.BIG_MODELS_DIR != config.MODELS_DIR else "") + f"\nEstimated time: about {max(1, len(ents)) * 25} minutes to {max(1, len(ents)) * 45} minutes on a GPU (longer for the MoE files and on a slow disk). Safe to stop; use --resume to continue.")
    if not a.yes:
        if sys.stdin and sys.stdin.isatty():
            if input("Start? [y/N] ").strip().lower() not in ("y", "yes"): sys.exit("Cancelled.")
        else: sys.exit("Not running unattended without --yes.")
    for e in ents:
        names = {f"{e.get('save_as') or e['file']}|{lab}": lab for lab, _ in moe_variants(e.get("save_as") or e["file"])}
        if a.resume and all(STATE["rows"].get(k, {}).get("done") for k in names): print("skip (resume):", e["name"]); continue
        if not a.resume:
            for k in names: STATE["rows"].pop(k, None)
        path = modelstore.target_path(e); fresh = not modelstore.have(e)
        if fresh:
            ok, msg = modelstore.download(e)
            print(f"  {e['name']}: {msg}", flush=True)
            if not ok:
                for k, lab in names.items(): STATE["rows"][k] = {"model": e["name"], "label": lab, "size_gb": round(e["size_bytes"] / 2**30, 1), "load": {"ok": False, "error": msg}, "done": True, "skipped": True}
                save(); report(hw); continue
        for label, moe in moe_variants(e.get("save_as") or e["file"]):
            try: do_model(path, label, moe)
            except Exception as ex:  # one broken model never stops the run
                STATE["rows"][f"{os.path.basename(path)}|{label}"] = {"model": os.path.basename(path), "label": label, "size_gb": round(e["size_bytes"] / 2**30, 1), "load": {"ok": False, "error": f"benchmark script error: {ex}"}, "done": True}; save()
                stop()
            report(hw)
        stop()
        if fresh and not a.keep:  # only a file this run downloaded, only inside the models folder
            if modelstore.delete_downloaded(e): print(f"  deleted {os.path.basename(path)} (use --keep to keep downloads)")
    if a.install:
        e = modelstore.entry(a.install); ok, msg = modelstore.download(e); print(f"Install {e['name']}: {msg}")
    stop(); report(hw)


if __name__ == "__main__":
    main()
