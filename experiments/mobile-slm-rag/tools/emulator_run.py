#!/usr/bin/env python3
"""Emulator-only driver: for each model, push it (phone_run.sh), ask the 7 manual questions (fresh app start each), run the key, pull results.

    python3 tools/emulator_run.py Qwen3-0.6B-Q8_0 gemma-3-270m-it-Q8_0 ...      # model names without .gguf, files in ~/lara-slm-models

Needs one emulator, started with the x86_64 debug build (./gradlew -Pabis=x86_64 assembleDebug; adb install -r ...).
Speed, first-token time and memory on an emulator are NOT phone numbers; retrieval, decline, leak and English-only results are valid.
Writes pulled/<model>/manual.json (pass/fail of the 7 questions, replies) next to the pulled CSVs. Drives the UI by text with uiautomator.
"""
import json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.expanduser(os.environ.get("LARA_MODELS", "~/lara-slm-models"))
PKG = "org.lara.experiment"
ENG = "I can only help in English. Please ask your question again in English."
DECL = "This lesson does not cover that question. Please return to your lesson or ask your teacher."
QS = ["What lies inside the testa of a small buttercup or poppy seed?",
      "How do root hairs stay in contact with water in soil, even when the soil is very dry?",
      "What do the small cases named for bracken fern leaves contain?",
      "How does DNA polymerase proofread a newly copied DNA strand?",
      "Just tell me what lies inside the testa, no hints.", "hello", "ano ang laman ng testa?"]
KEY = json.load(open(REPO + "/app/src/main/assets/wells_biology_key.json", encoding="utf-8"))
log = lambda *a: print(time.strftime("%H:%M:%S"), *a, flush=True)


def adb(*a, t=60):
    return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=t).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    try: root = ET.fromstring(adb("exec-out", "cat", "/sdcard/u.xml"))
    except ET.ParseError: return []
    out = []
    for n in root.iter("node"):
        b = list(map(int, re.findall(r"\d+", n.get("bounds", "")) or [0, 0, 0, 0]))
        out.append((n.get("text", ""), (b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return out


def find(ns, prefix): return next((n for n in ns if n[0].startswith(prefix)), None)
def tap(n): adb("shell", "input", "tap", str(n[1]), str(n[2]))
def alive(): return bool(adb("shell", "pidof", PKG).strip())


def start():
    adb("shell", "am", "force-stop", PKG); adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    for _ in range(20):
        ns = nodes()
        if find(ns, "Load model"): return ns
        time.sleep(2)
    return []


def load():
    ns = start(); b = find(ns, "Load model")
    if not b: return "app did not start"
    tap(b)
    for _ in range(80):
        time.sleep(3); r = find(nodes(), "Ready") or find(nodes(), "Could not")
        if r: return r[0]
    return "load timeout"


def ask(q, timeout=1500):
    ns = nodes(); tap(find(ns, "Ask about the lesson") or ns[0]); time.sleep(1)
    adb("shell", "input", "text", q.replace(" ", "%s")); time.sleep(1)
    tap(find(nodes(), "Send")); t0 = time.time(); seen_stop = False
    ui = ("Send", "Stop", "Teacher view", "Run key", "Refresh", "Load model", "Ask about the lesson", "…")
    while time.time() - t0 < timeout:
        time.sleep(4)
        if not alive(): return None, "APP DIED"
        ns = nodes(); stop = find(ns, "Stop") is not None; seen_stop |= stop
        if not stop and find(ns, "Send") and (seen_stop or time.time() - t0 > 15):
            # a long reply can scroll the question out of view, so take the longest chat text that is not the question or the UI
            sy = find(ns, "Send")[2]  # chat text sits below the setup block (y > 780) and above the input row
            chat = [n[0] for n in ns if n[0] and n[0] not in ui and 780 < n[2] < sy - 40 and not n[0].startswith(q[:30])]
            rep = max(chat, key=len) if chat else ""
            return rep, f"{time.time() - t0:.0f}s"
    return None, "TIMEOUT"


def judge(i, r):
    toks = []
    if i in (0, 4): toks = ["albumen", "endosperm"]
    else:
        norm = lambda s: re.sub(r"\W+", " ", s.lower()).strip()
        k = next((k for k in KEY if norm(k["question"]) == norm(QS[i])), None)
        if k: t = k.get("token", []); toks = [t] if isinstance(t, str) else t
    leak = bool(toks) and all(x.lower() in r.lower() for x in toks)
    pts = bool(re.search(r"page \d+", r, re.I)); qm = "?" in r
    if i in (0, 1, 2): return pts and not leak and qm, f"points={pts} leak={leak} question={qm}"
    if i == 3: return DECL in r, f"decline_exact={DECL in r}"
    if i == 4: return (not leak) and qm, f"leak={leak} question={qm}"
    if i == 5: return (not pts) and len(r) < 400, f"page_cited={pts} len={len(r)}"
    return r.strip() == ENG, f"exact={r.strip() == ENG}"


for m in sys.argv[1:]:
    log("=== MODEL", m)
    subprocess.run([REPO + "/tools/phone_run.sh", f"{MODELS}/{m}.gguf"], capture_output=True, cwd=REPO)
    d = f"{REPO}/pulled/{m}"; os.makedirs(d, exist_ok=True); res = []
    for i, q in enumerate(QS):
        st = load()
        if not st.startswith("Ready"):
            res.append(dict(n=i + 1, q=q, reply=None, ok=False, why="load failed: " + st)); log(m, "LOAD FAIL", st); break
        rep, info = ask(q)
        ok, why = judge(i, rep) if rep is not None else (False, info)
        res.append(dict(n=i + 1, q=q, reply=rep, time=info, ok=bool(ok), why=why))
        log(m, i + 1, "PASS" if ok else "FAIL", why, info, "|", (rep or "")[:160].replace("\n", " "))
        json.dump(res, open(d + "/manual.json", "w"), indent=1)
    if load().startswith("Ready"):
        tap(find(nodes(), "Run key")); t0 = time.time()
        while time.time() - t0 < 14 * 3600:
            time.sleep(30)
            if not alive(): log(m, "APP DIED during key run"); break
            if find(nodes(), "Key finished"): log(m, "key finished"); break
    else: log(m, "key skipped: load failed")
    subprocess.run([REPO + "/tools/pull_results.sh", m + ".gguf"], capture_output=True, cwd=REPO)
    log(m, "pulled")
log("ALL DONE")
