#!/usr/bin/env python3
"""
L.A.R.A AI feasibility spike runner (#73, rules/socratic-ai-guardrails.md section 5).

No UI and nothing beyond the Python standard library. It drives llama-server directly (the mock
hub is not involved) and is never shipped with the Hub.

  validate  check prompts.json and print its coverage (no model needed)
  run       start llama-server for each candidate GGUF at 1, 2 and 4 slots and send every prompt
            with contracts/ai/socratic_system_prompt.txt filled in unchanged; save replies, time
            to first token, tokens per second and peak RAM
  sheets    write one blind score sheet per scorer: unique replies shuffled, model names hidden
  report    merge two filled sheets: agreement rate, the disagreements to settle, pass rates per
            criterion, latency and RAM, as Markdown for docs/benchmarks/ai_model_evaluation.md

From the repo root:
  py server/ai_eval/run_eval.py validate
  py server/ai_eval/run_eval.py run --llama-server D:/llama/llama-server.exe \
      --model qwen2.5-1.5b=D:/models/qwen2.5-1.5b-instruct-q4_k_m.gguf \
      --model gemma-2-2b=D:/models/gemma-2-2b-it-Q4_K_M.gguf --merge-system gemma-2-2b \
      --model minicpm5-2b=D:/models/MiniCPM5-2B-Q4_K_M.gguf --no-think minicpm5-2b \
      --out server/ai_eval/results/hub-pc
  py server/ai_eval/run_eval.py sheets --out server/ai_eval/results/hub-pc --scorers scorer_a scorer_b \n      [--models qwen2.5-1.5b minicpm5-2b]   # only these models on the sheets
  py server/ai_eval/run_eval.py report --out server/ai_eval/results/hub-pc \
      --scores server/ai_eval/results/hub-pc/score_scorer_a.csv server/ai_eval/results/hub-pc/score_scorer_b.csv

Scorers must not open replies.jsonl or blind_key.json: they name the model behind each reply.
"""

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import re
import socket
import statistics
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SYSTEM_PROMPT_FILE = ROOT / "contracts" / "ai" / "socratic_system_prompt.txt"
PROMPTS_FILE = Path(__file__).with_name("prompts.json")

CONTEXT_TOKENS = 2048  # rules/socratic-ai-guardrails.md section 1: the window every candidate gets
REPLY_LANGUAGE = {"EN": "English", "FIL": "Filipino"}  # EVENT_AI_CHAT_REQUEST.language -> {reply_language}
INPUT_LANGUAGES = ("en", "fil", "taglish")
CATEGORIES = ("on_lesson", "direct_answer", "not_covered", "wrong_subject", "jailbreak",
              "short_or_misspelled", "language_mismatch", "empty_lesson")
CRITERIA = ("no_direct_answer", "grounded", "socratic_shape", "language")
SHEET_COLUMNS = ["reply_id", "prompt_id", "selected_language", "category", "covered_by_lesson",
                 "lesson_text", "learner_message", "expected_answer", "answer_flag", "reply",
                 *CRITERIA, "comment"]
HEALTH_TIMEOUT_S = 600   # a 3B model can take minutes to load from a slow disk
REQUEST_TIMEOUT_S = 900  # four concurrent generations on a weak CPU are slow, not broken


# ---------------------------------------------------------------- prompt set

def load_prompt_set(path=PROMPTS_FILE):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def validate(data):
    """Returns a list of problems; empty means the set is usable."""
    problems = []
    if not isinstance(data.get("version"), int):
        problems.append("version must be an integer")
    lessons = data.get("lessons", {})
    chunk_ids = set()
    for lid, lesson in lessons.items():
        if not isinstance(lesson.get("grade"), int) or not 1 <= lesson["grade"] <= 12:
            problems.append(f"{lid}: grade must be 1 to 12")
        for field in ("subject", "title"):
            if not lesson.get(field):
                problems.append(f"{lid}: missing {field}")
        orders = set()
        for chunk in lesson.get("chunks", []):
            if chunk.get("id") in chunk_ids:
                problems.append(f"{lid}: duplicate chunk id {chunk.get('id')}")
            chunk_ids.add(chunk.get("id"))
            if chunk.get("order_index") in orders:
                problems.append(f"{lid}: duplicate order_index {chunk.get('order_index')}")
            orders.add(chunk.get("order_index"))
            if not str(chunk.get("text", "")).strip():
                problems.append(f"{lid}: chunk {chunk.get('id')} has no text")
    seen = set()
    for p in data.get("prompts", []):
        pid = p.get("id")
        if pid in seen:
            problems.append(f"duplicate prompt id {pid}")
        seen.add(pid)
        lesson = lessons.get(p.get("lesson_id"))
        if lesson is None:
            problems.append(f"{pid}: unknown lesson_id {p.get('lesson_id')}")
            continue
        if p.get("language") not in REPLY_LANGUAGE:
            problems.append(f"{pid}: language must be one of {sorted(REPLY_LANGUAGE)}")
        if p.get("input_language") not in INPUT_LANGUAGES:
            problems.append(f"{pid}: input_language must be one of {INPUT_LANGUAGES}")
        if p.get("category") not in CATEGORIES:
            problems.append(f"{pid}: unknown category {p.get('category')}")
        if not isinstance(p.get("message"), str) or not p["message"]:
            problems.append(f"{pid}: message must be a non-empty string")
        answers = p.get("expected_answer")
        if not isinstance(answers, list) or not answers or not all(isinstance(a, str) and a.strip() for a in answers):
            problems.append(f"{pid}: expected_answer must be a non-empty list of strings")
        lesson_chunks = {c["id"] for c in lesson.get("chunks", [])}
        grounded = p.get("grounded_chunk_ids", [])
        if not set(grounded) <= lesson_chunks:
            problems.append(f"{pid}: grounded_chunk_ids not in {p['lesson_id']}: {sorted(set(grounded) - lesson_chunks)}")
        if p.get("covered_by_lesson") is True and not grounded:
            problems.append(f"{pid}: covered_by_lesson but no grounded_chunk_ids")
        if p.get("covered_by_lesson") is False and grounded:
            problems.append(f"{pid}: not covered_by_lesson but has grounded_chunk_ids")
        if (p.get("category") == "empty_lesson") != (not lesson_chunks):
            problems.append(f"{pid}: empty_lesson prompts, and only they, use a lesson with no chunks")
    return problems


def coverage(data):
    lessons = data["lessons"]
    prompts = data["prompts"]

    def count(key):
        out = {}
        for p in prompts:
            out[key(p)] = out.get(key(p), 0) + 1
        return out

    lines = [f"prompt set version {data['version']}: {len(prompts)} prompts over {len(lessons)} lessons"]
    lines.append(f"  category:        {count(lambda p: p['category'])}")
    lines.append(f"  input language:  {count(lambda p: p['input_language'])}")
    lines.append(f"  selected (reply) language: {count(lambda p: p['language'])}")
    grades = sorted({lessons[p['lesson_id']]['grade'] for p in prompts})
    lines.append(f"  grades: {grades}")
    lines.append(f"  subjects: {sorted({lessons[p['lesson_id']]['subject'] for p in prompts})}")
    warnings = []
    missing_cats = [c for c in CATEGORIES if c not in count(lambda p: p['category'])]
    if missing_cats:
        warnings.append(f"no prompts for categories {missing_cats}")
    if grades != list(range(1, 13)):
        warnings.append(f"grades not covered: {sorted(set(range(1, 13)) - set(grades))}")
    if not data.get("review", {}).get("filipino_reviewer"):
        warnings.append("Filipino and Taglish prompts are not reviewed yet (review.filipino_reviewer is empty)")
    return lines, warnings


def lesson_text(lesson):
    """{lesson_chunks}: chunks in order_index order, heading then text, separated by a blank line."""
    blocks = []
    for c in sorted(lesson.get("chunks", []), key=lambda c: c["order_index"]):
        blocks.append(f"{c['heading']}\n{c['text']}" if c.get("heading") else c["text"])
    return "\n\n".join(blocks)


def system_prompt(template, lesson, language):
    # str.replace, not str.format: the template is loaded unchanged and may contain other braces.
    return template.replace("{reply_language}", REPLY_LANGUAGE[language]).replace("{lesson_chunks}", lesson_text(lesson))


def chat_messages(system, message, merge_system):
    # Some chat templates (Gemma 2) reject a system role; then the same prompt text leads the user turn.
    if merge_system:
        return [{"role": "user", "content": f"{system}\n\n{message}"}]
    return [{"role": "system", "content": system}, {"role": "user", "content": message}]


def _normalize(text):
    text = unicodedata.normalize("NFKC", text).lower()
    text = re.sub(r"(?<=\d),(?=\d{3})", "", text)       # 1,500 -> 1500
    text = re.sub(r"\s*([=/+×÷*()-])\s*", r"\1", text)  # x = 2 -> x=2, (x - 2) -> (x-2)
    return re.sub(r"\s+", " ", text).strip()


def answer_flags(reply, expected_answers):
    """The expected answers that appear in a reply (criterion 1 pre-check; a human confirms)."""
    norm = _normalize(reply)
    hits = []
    for answer in expected_answers:
        a = _normalize(answer)
        if re.search(r"(?<![0-9a-z])" + re.escape(a) + r"(?![0-9a-z])", norm):
            hits.append(answer)
    return hits


# ---------------------------------------------------------------- machine facts

def hardware():
    info = {"os": platform.platform(), "cpu": platform.processor(), "logical_cpus": os.cpu_count(), "ram_bytes": None}
    if sys.platform == "win32":
        import ctypes
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as k:
                info["cpu"] = winreg.QueryValueEx(k, "ProcessorNameString")[0].strip()
        except OSError:
            pass

        class MemoryStatus(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong)] + [
                (n, ctypes.c_ulonglong) for n in ("ullTotalPhys", "ullAvailPhys", "ullTotalPageFile",
                                                  "ullAvailPageFile", "ullTotalVirtual", "ullAvailVirtual",
                                                  "ullAvailExtendedVirtual")]
        status = MemoryStatus()
        status.dwLength = ctypes.sizeof(MemoryStatus)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            info["ram_bytes"] = status.ullTotalPhys
    elif sys.platform.startswith("linux"):
        try:
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.startswith("model name"):
                    info["cpu"] = line.split(":", 1)[1].strip()
                    break
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    info["ram_bytes"] = int(line.split()[1]) * 1024
        except OSError:
            pass
    return info


def peak_rss_bytes(pid):
    """The OS's own high-water mark for the process (peak working set / VmHWM): no sampling gaps."""
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                (n, ctypes.c_size_t) for n in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                                               "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                                               "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.OpenProcess.restype = wintypes.HANDLE
        k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        k32.K32GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD]
        k32.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return None
        try:
            counters = Counters()
            counters.cb = ctypes.sizeof(Counters)
            if k32.K32GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                return counters.PeakWorkingSetSize
            return None
        finally:
            k32.CloseHandle(handle)
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmHWM:"):
                return int(line.split()[1]) * 1024
    except OSError:
        pass
    return None


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# ---------------------------------------------------------------- llama-server

def _post(url, body, timeout=60):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def _get(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LlamaServer:
    def __init__(self, binary, gguf, slots, args, log_path, template_kwargs=None):
        self.port = _free_port()
        self.template_kwargs = template_kwargs or {}
        self.base = f"http://127.0.0.1:{self.port}"
        # -c is the server's total context; each slot needs the full 2048-token window.
        self.argv = [binary, "-m", str(gguf), "-c", str(CONTEXT_TOKENS * slots), "-np", str(slots),
                     "--host", "127.0.0.1", "--port", str(self.port), "-ngl", str(args.gpu_layers)]
        if args.threads:
            self.argv += ["-t", str(args.threads)]
        self.log_path = log_path

    def __enter__(self):
        self.log = open(self.log_path, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(self.argv, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + HEALTH_TIMEOUT_S
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"llama-server exited with {self.proc.returncode}; see {self.log_path}")
            try:
                if _get(f"{self.base}/health", timeout=5).get("status") == "ok":
                    return self
            except (urllib.error.URLError, OSError, ValueError):
                pass
            time.sleep(1)
        self.__exit__(None, None, None)
        raise RuntimeError(f"llama-server not healthy after {HEALTH_TIMEOUT_S}s; see {self.log_path}")

    def __exit__(self, *exc):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        self.log.close()

    def slot_context(self):
        try:
            return _get(f"{self.base}/props").get("default_generation_settings", {}).get("n_ctx")
        except (urllib.error.URLError, OSError, ValueError):
            return None

    def prompt_tokens(self, messages):
        """Tokens of the full templated prompt, or None if this llama-server lacks the endpoints."""
        try:
            prompt = _post(f"{self.base}/apply-template",
                           {"messages": messages, "chat_template_kwargs": self.template_kwargs})["prompt"]
            return len(_post(f"{self.base}/tokenize", {"content": prompt, "add_special": True})["tokens"])
        except (urllib.error.URLError, OSError, ValueError, KeyError):
            return None

    def ask(self, messages, args):
        body = {"messages": messages, "stream": True, "max_tokens": args.max_tokens,
                "temperature": args.temperature, "seed": args.seed, "cache_prompt": True,
                "chat_template_kwargs": self.template_kwargs}
        req = urllib.request.Request(f"{self.base}/v1/chat/completions", data=json.dumps(body).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        parts, reasoning, timings, first, error = [], [], None, None, None
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as r:
                for raw in r:
                    line = raw.decode("utf-8").strip()
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    chunk = json.loads(data)
                    timings = chunk.get("timings", timings)
                    for choice in chunk.get("choices", []):
                        delta = choice.get("delta") or {}
                        # llama-server splits a thinking model's <think> text out of content; keep it
                        # so a reply that still thought (and spent its tokens on that) can be spotted.
                        if delta.get("reasoning_content"):
                            reasoning.append(delta["reasoning_content"])
                        piece = delta.get("content")
                        if piece:
                            first = first or time.perf_counter()
                            parts.append(piece)
        except urllib.error.HTTPError as e:
            error = f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}"
        except (urllib.error.URLError, OSError, ValueError) as e:
            error = f"{type(e).__name__}: {e}"
        end = time.perf_counter()
        predicted = (timings or {}).get("predicted_n", len(parts))
        tps = (timings or {}).get("predicted_per_second")
        if tps is None and first and end > first and predicted:
            tps = predicted / (end - first)
        return {"reply": "".join(parts), "reasoning": "".join(reasoning), "error": error,
                "ttft_ms": round((first - start) * 1000, 1) if first else None,
                "total_ms": round((end - start) * 1000, 1),
                "completion_tokens": predicted, "tokens_per_second": round(tps, 2) if tps else None}


def llama_server_version(binary):
    try:
        out = subprocess.run([binary, "--version"], capture_output=True, text=True, timeout=60)
        text = (out.stdout + out.stderr).strip().splitlines()
        return " | ".join(line for line in text if "version" in line.lower() or "built" in line.lower()) or None
    except (OSError, subprocess.TimeoutExpired):
        return None


def cmd_run(args):
    data = load_prompt_set(args.prompts)
    problems = validate(data)
    if problems:
        sys.exit("prompts.json is invalid:\n  " + "\n  ".join(problems))
    template = SYSTEM_PROMPT_FILE.read_bytes().decode("utf-8")
    out = Path(args.out)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    runs_path, replies_path = out / "runs.json", out / "replies.jsonl"

    settings = {"context_tokens_per_slot": CONTEXT_TOKENS, "max_tokens": args.max_tokens,
                "temperature": args.temperature, "seed": args.seed, "gpu_layers": args.gpu_layers,
                "threads": args.threads}
    header = {"prompt_set_version": data["version"],
              "filipino_reviewer": data.get("review", {}).get("filipino_reviewer"),
              "system_prompt_sha256": hashlib.sha256(template.encode("utf-8")).hexdigest(),
              "settings": settings}
    state = {**header, "hardware": hardware(), "llama_server_version": llama_server_version(args.llama_server),
             "models": {}, "runs": {}}
    if runs_path.exists():
        previous = json.loads(runs_path.read_text(encoding="utf-8"))
        changed = [k for k in header if previous.get(k) != header[k]]
        if changed:
            sys.exit(f"{out} holds a run with different {changed}; use a new --out folder")
        state["models"], state["runs"] = previous["models"], previous["runs"]

    models = []
    for spec in args.model:
        name, _, path = spec.partition("=")
        if not name or not path or not Path(path).is_file():
            sys.exit(f"--model {spec!r}: expected NAME=PATH to an existing .gguf")
        models.append((name, Path(path)))
    lessons = data["lessons"]

    for name, gguf in models:
        merge = name in args.merge_system
        template_kwargs = {"enable_thinking": False} if name in args.no_think else {}
        if name not in state["models"]:
            print(f"[{name}] hashing {gguf.name}...", flush=True)
            state["models"][name] = {"file": gguf.name, "size_bytes": gguf.stat().st_size,
                                     "sha256": sha256_file(gguf), "merge_system": merge,
                                     "chat_template_kwargs": template_kwargs}
        conversations = [(p, chat_messages(system_prompt(template, lessons[p["lesson_id"]], p["language"]),
                                           p["message"], merge)) for p in data["prompts"]]
        for slots in args.slots:
            key = f"{name}@{slots}"
            if key in state["runs"]:
                print(f"[{key}] already in {runs_path.name}, skipping")
                continue
            print(f"[{key}] starting llama-server...", flush=True)
            with LlamaServer(args.llama_server, gguf, slots, args, out / "logs" / f"{name}_{slots}slots.log",
                             template_kwargs) as srv:
                n_ctx = srv.slot_context()
                if n_ctx is not None and n_ctx < CONTEXT_TOKENS:
                    raise RuntimeError(f"{key}: each slot has {n_ctx} tokens of context, needs {CONTEXT_TOKENS}")
                counts = {p["id"]: srv.prompt_tokens(m) for p, m in conversations}
                over = [f"{pid} ({n})" for pid, n in counts.items() if n and n + args.max_tokens > CONTEXT_TOKENS]
                if over:
                    raise RuntimeError(f"{key}: prompt + {args.max_tokens} reply tokens exceed {CONTEXT_TOKENS}: {over}")
                srv.ask(conversations[0][1], args)  # warm-up, not recorded
                started = time.perf_counter()
                with ThreadPoolExecutor(max_workers=slots) as pool:
                    results = list(pool.map(lambda c: srv.ask(c[1], args), conversations))
                wall = time.perf_counter() - started
                peak = peak_rss_bytes(srv.proc.pid)
            rows = [{"model": name, "slots": slots, "prompt_id": p["id"], "prompt_tokens": counts[p["id"]], **r}
                    for (p, _), r in zip(conversations, results)]
            with open(replies_path, "a", encoding="utf-8") as f:
                for row in rows:
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
            tokens = sum(r["completion_tokens"] or 0 for r in results)
            state["runs"][key] = {"model": name, "slots": slots, "n_ctx_per_slot": n_ctx, "peak_ram_bytes": peak,
                                  "wall_seconds": round(wall, 1), "completion_tokens": tokens,
                                  "throughput_tps": round(tokens / wall, 2) if wall else None,
                                  "errors": sum(1 for r in results if r["error"]), "server_args": srv.argv[1:]}
            runs_path.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")
            peak_text = f"{peak / 2**20:.0f} MB" if peak else "n/a"
            print(f"[{key}] {len(rows)} replies, {state['runs'][key]['errors']} errors, {wall:.0f}s, "
                  f"peak RAM {peak_text}", flush=True)
            thought = sum(1 for r in results if r["reasoning"])
            if thought:
                print(f"[{key}] WARNING: {thought} replies spent tokens thinking; try --no-think {name}", flush=True)


# ---------------------------------------------------------------- scoring

def _read_replies(out):
    with open(Path(out) / "replies.jsonl", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _quoted(values):
    # Quoted so a spreadsheet keeps "5/6" as text instead of turning it into a date.
    return " | ".join(f'"{v}"' for v in values)


def cmd_sheets(args):
    out = Path(args.out)
    data = load_prompt_set(args.prompts)
    prompts = {p["id"]: p for p in data["prompts"]}
    targets = [out / f"score_{s}.csv" for s in args.scorers] + [out / "blind_key.json"]
    existing = [t.name for t in targets if t.exists()]
    if existing and not args.force:
        sys.exit(f"{existing} already exist and may hold scores; pass --force to overwrite")

    replies = [row for row in _read_replies(out) if not row["error"]]
    if args.models:
        unknown = sorted(set(args.models) - {row["model"] for row in replies})
        if unknown:
            sys.exit(f"--models: {unknown} have no replies in {out / 'replies.jsonl'}")
        replies = [row for row in replies if row["model"] in args.models]

    unique = {}
    for row in replies:
        key = (row["model"], row["prompt_id"], row["reply"].strip())
        unique.setdefault(key, []).append(row["slots"])
    keys = sorted(unique)
    random.Random(args.seed).shuffle(keys)
    blind = {f"R{i + 1:04d}": {"model": m, "prompt_id": pid, "reply": reply, "slots": sorted(unique[(m, pid, reply)])}
             for i, (m, pid, reply) in enumerate(keys)}
    (out / "blind_key.json").write_text(json.dumps(blind, indent=2, ensure_ascii=False), encoding="utf-8")

    for scorer in args.scorers:
        with open(out / f"score_{scorer}.csv", "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=SHEET_COLUMNS)
            w.writeheader()
            for rid, entry in blind.items():
                p = prompts[entry["prompt_id"]]
                w.writerow({"reply_id": rid, "prompt_id": p["id"], "selected_language": p["language"],
                            "category": p["category"], "covered_by_lesson": p["covered_by_lesson"],
                            "lesson_text": lesson_text(data["lessons"][p["lesson_id"]]) or "(no lesson text)",
                            "learner_message": p["message"], "expected_answer": _quoted(p["expected_answer"]),
                            "answer_flag": _quoted(answer_flags(entry["reply"], p["expected_answer"])),
                            "reply": entry["reply"]})
    print(f"{len(blind)} unique replies to score in {', '.join(t.name for t in targets[:-1])}")


def _verdict(cell):
    v = (cell or "").strip().lower()
    if v in ("p", "pass", "1", "y", "yes"):
        return True
    if v in ("f", "fail", "0", "n", "no"):
        return False
    return None


def _read_scores(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return {row["reply_id"]: {c: _verdict(row.get(c)) for c in CRITERIA} for row in csv.DictReader(f)}


def _pct(passed, total):
    return f"{passed}/{total} ({100 * passed / total:.0f}%)" if total else "n/a"


def _p95(values):
    values = sorted(values)
    return values[max(0, math.ceil(0.95 * len(values)) - 1)]


def cmd_report(args):
    out = Path(args.out)
    data = load_prompt_set(args.prompts)
    prompts = {p["id"]: p for p in data["prompts"]}
    state = json.loads((out / "runs.json").read_text(encoding="utf-8"))
    blind = json.loads((out / "blind_key.json").read_text(encoding="utf-8"))
    names = [Path(s).stem.removeprefix("score_") for s in args.scores]
    a, b = (_read_scores(s) for s in args.scores)

    disagreements_path = out / "disagreements.csv"
    settled = {}
    if disagreements_path.exists():
        with open(disagreements_path, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                if _verdict(row.get("final")) is not None:
                    settled[(row["reply_id"], row["criterion"])] = row["final"]

    final, agree, compared, unscored, disputes = {}, {c: 0 for c in CRITERIA}, {c: 0 for c in CRITERIA}, 0, []
    for rid in blind:
        for c in CRITERIA:
            va, vb = a.get(rid, {}).get(c), b.get(rid, {}).get(c)
            if va is None or vb is None:
                unscored += 1
                continue
            compared[c] += 1
            if va == vb:
                agree[c] += 1
                final[(rid, c)] = va
            else:
                disputes.append({"reply_id": rid, "prompt_id": blind[rid]["prompt_id"], "criterion": c,
                                 names[0]: "P" if va else "F", names[1]: "P" if vb else "F",
                                 "final": settled.get((rid, c), "")})
                if (rid, c) in settled:
                    final[(rid, c)] = _verdict(settled[(rid, c)])
    with open(disagreements_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["reply_id", "prompt_id", "criterion", *names, "final"])
        w.writeheader()
        w.writerows(disputes)
    unsettled = sum(1 for d in disputes if _verdict(d["final"]) is None)

    reply_ids = {(e["model"], e["prompt_id"], e["reply"]): rid for rid, e in blind.items()}
    run_rows = {}
    for row in _read_replies(out):
        run_rows.setdefault((row["model"], row["slots"]), []).append(row)

    L = ["# AI evaluation results (generated by run_eval.py report)", "",
         "**Developer-scored.** The prompt set was written and every reply was scored by the L.A.R.A development "
         "team, not by teachers or an adviser. These pass rates are not validated by educators.", ""]
    reviewer = data.get("review", {}).get("filipino_reviewer")
    if unscored or unsettled or not reviewer:
        reasons = []
        if unscored or unsettled:
            reasons.append(f"{unscored} criterion cells unscored by one or both scorers, {unsettled} disagreements "
                           f"not settled in `disagreements.csv` (rates below use only settled cells)")
        if not reviewer:
            reasons.append("the Filipino and Taglish prompts are not reviewed by a Filipino-fluent teammate yet")
        L += ["**PROVISIONAL:** " + "; ".join(reasons) + ".", ""]
    hw = state["hardware"]
    L += [f"* Prompt set: version {state['prompt_set_version']}, {len(prompts)} prompts; Filipino review: "
          f"{reviewer or 'NOT REVIEWED YET'}",
          f"* Hardware: {hw['cpu']}, {hw['logical_cpus']} logical CPUs, "
          f"{(hw['ram_bytes'] or 0) / 2**30:.1f} GB RAM, {hw['os']}",
          f"* llama-server: {state['llama_server_version']}; settings: {json.dumps(state['settings'])}",
          f"* Scorers: {names[0]}, {names[1]}; agreement rate "
          + _pct(sum(agree.values()), sum(compared.values())) + " of criterion cells ("
          + ", ".join(f"{c} {_pct(agree[c], compared[c])}" for c in CRITERIA) + ")", ""]
    scored = {e["model"] for e in blind.values()}
    not_scored = sorted({m for m, _ in run_rows} - scored)
    if not_scored:
        L += [f"* Not on the score sheets (replies kept in `replies.jsonl`): {', '.join(not_scored)}", ""]

    L += ["## Pass rate per criterion", "",
          "| Model | Slots | Replies | No direct answer | Grounded | Socratic shape | Language | All four | Answer pre-flags |",
          "|---|---|---|---|---|---|---|---|---|"]
    by_model = {}
    for (model, slots), rows in sorted(run_rows.items()):
        if model not in scored:
            continue
        counts = {c: [0, 0] for c in (*CRITERIA, "all")}
        flags = 0
        for row in rows:
            if row["error"]:
                continue
            rid = reply_ids.get((model, row["prompt_id"], row["reply"].strip()))
            verdicts = [final.get((rid, c)) for c in CRITERIA]
            for c, v in zip(CRITERIA, verdicts):
                if v is not None:
                    counts[c][1] += 1
                    counts[c][0] += v
            if None not in verdicts:
                counts["all"][1] += 1
                counts["all"][0] += all(verdicts)
                by_model.setdefault(model, []).append((prompts[row["prompt_id"]], all(verdicts)))
            flags += bool(answer_flags(row["reply"], prompts[row["prompt_id"]]["expected_answer"]))
        L.append(f"| {model} | {slots} | {len(rows)} | " + " | ".join(_pct(*counts[c]) for c in (*CRITERIA, "all"))
                 + f" | {flags} |")

    L += ["", "## Speed and memory", "",
          "| Model | File | Slots | TTFT median (ms) | TTFT p95 (ms) | Tokens/s per reply (median) | "
          "Throughput, all slots (tokens/s) | Peak RAM (MB) | Errors |",
          "|---|---|---|---|---|---|---|---|---|"]
    for key, run in sorted(state["runs"].items(), key=lambda kv: (kv[1]["model"], kv[1]["slots"])):
        rows = [r for r in run_rows.get((run["model"], run["slots"]), []) if not r["error"]]
        ttft = [r["ttft_ms"] for r in rows if r["ttft_ms"] is not None]
        tps = [r["tokens_per_second"] for r in rows if r["tokens_per_second"]]
        peak = f"{run['peak_ram_bytes'] / 2**20:.0f}" if run["peak_ram_bytes"] else "n/a"
        L.append(f"| {run['model']} | {state['models'][run['model']]['file']} | {run['slots']} | "
                 f"{statistics.median(ttft):.0f} | {_p95(ttft):.0f} | {statistics.median(tps):.1f} | "
                 f"{run['throughput_tps']} | {peak} | {run['errors']} |" if ttft and tps else
                 f"| {run['model']} | {state['models'][run['model']]['file']} | {run['slots']} | n/a | n/a | n/a | "
                 f"{run['throughput_tps']} | {peak} | {run['errors']} |")

    L += ["", "## All four criteria by prompt category and selected language (all slot counts)", "",
          "| Model | " + " | ".join(CATEGORIES) + " | EN | FIL |", "|---|" + "---|" * (len(CATEGORIES) + 2)]
    for model, items in sorted(by_model.items()):
        cells = []
        for group in (*CATEGORIES, "EN", "FIL"):
            hits = [ok for p, ok in items if group in (p["category"], p["language"])]
            cells.append(_pct(sum(hits), len(hits)))
        L.append(f"| {model} | " + " | ".join(cells) + " |")

    (out / "report.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print(f"wrote {out / 'report.md'}; {len(disputes)} disagreements ({unsettled} unsettled) in {disagreements_path}")


def cmd_validate(args):
    data = load_prompt_set(args.prompts)
    problems = validate(data)
    lines, warnings = coverage(data) if not problems else ([], [])
    print("\n".join(lines))
    for w in warnings:
        print(f"WARNING: {w}")
    if problems:
        sys.exit("prompts.json is invalid:\n  " + "\n  ".join(problems))
    template = SYSTEM_PROMPT_FILE.read_bytes().decode("utf-8")
    for placeholder in ("{reply_language}", "{lesson_chunks}"):
        if placeholder not in template:
            sys.exit(f"{SYSTEM_PROMPT_FILE} has no {placeholder}")
    print("OK")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--prompts", default=str(PROMPTS_FILE), help="prompt set (default: prompts.json beside this script)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("validate", help="check prompts.json and print its coverage")

    run = sub.add_parser("run", help="run candidate models through llama-server")
    run.add_argument("--llama-server", required=True, help="path to the llama-server binary")
    run.add_argument("--model", action="append", required=True, metavar="NAME=PATH", help="candidate GGUF (repeatable)")
    run.add_argument("--merge-system", action="append", default=[], metavar="NAME",
                     help="model whose chat template has no system role (e.g. Gemma 2)")
    run.add_argument("--no-think", action="append", default=[], metavar="NAME",
                     help="model with a thinking mode to switch off (enable_thinking=false, e.g. MiniCPM5)")
    run.add_argument("--slots", type=int, nargs="+", default=[1, 2, 4])
    run.add_argument("--out", required=True, help="results folder (re-running resumes it)")
    run.add_argument("--max-tokens", type=int, default=256)
    run.add_argument("--temperature", type=float, default=0.2)
    run.add_argument("--seed", type=int, default=42)
    run.add_argument("--threads", type=int, default=None, help="llama-server -t (default: its own choice)")
    run.add_argument("--gpu-layers", type=int, default=0, help="llama-server -ngl; 0 measures the CPU only")

    sheets = sub.add_parser("sheets", help="write blind score sheets")
    sheets.add_argument("--out", required=True)
    sheets.add_argument("--scorers", nargs=2, required=True, metavar="NAME")
    sheets.add_argument("--seed", type=int, default=73)
    sheets.add_argument("--force", action="store_true", help="overwrite existing sheets")
    sheets.add_argument("--models", nargs="+", metavar="NAME",
                        help="put only these models' replies on the sheets (default: every model that ran); "
                             "the others stay in replies.jsonl and can be scored later")

    report = sub.add_parser("report", help="merge two score sheets into report.md")
    report.add_argument("--out", required=True)
    report.add_argument("--scores", nargs=2, required=True, metavar="CSV")

    args = parser.parse_args(argv)
    {"validate": cmd_validate, "run": cmd_run, "sheets": cmd_sheets, "report": cmd_report}[args.command](args)


if __name__ == "__main__":
    main()
