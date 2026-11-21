"""N simulated learners chat at the same time. Standard library only.
    python concurrency_test.py [--users 1,2,3,5,8] [--requests 6] [--key sample/answer_key.json] [--url http://127.0.0.1:8000]
Each learner sends --requests questions one after another (a fixed rotation of 20 questions from the key, mixed types);
all learners start together. Reports time to first token (from sending the request), per-stream tokens/s, total tokens/s, errors."""
import argparse, datetime, json, os, statistics, threading, time, urllib.request
import config

ap = argparse.ArgumentParser()
ap.add_argument("--users", default="1,2,3,5,8"); ap.add_argument("--requests", type=int, default=6)
ap.add_argument("--key", default=os.path.join(config.HERE, "sample", "answer_key.json"))
ap.add_argument("--warmup", action="store_true", help="send one discarded request first (loads the model into memory)")
ap.add_argument("--json", default="", help="also write the rows as JSON to this path")
ap.add_argument("--url", default=f"http://127.0.0.1:{config.APP_PORT}")
a = ap.parse_args()
key = [k for k in json.load(open(a.key, encoding="utf-8")) if k["id"][0] == "Q"]
by_type = {}
for k in key: by_type.setdefault(k["type"].split(" ")[0], []).append(k["question"])
QUESTIONS = []  # fixed order: round-robin over the types until 20 questions
while len(QUESTIONS) < 20 and any(by_type.values()):
    for t in sorted(by_type):
        if by_type[t] and len(QUESTIONS) < 20: QUESTIONS.append(by_type[t].pop(0))


def chat(q):
    """One request; returns (ttft_s, tokens, tok_per_s, error)."""
    body = json.dumps({"message": q, "history": []}).encode()  # retriever, rerank, prompt and threshold come from the app's defaults
    t0 = time.time(); first = None; n = 0; err = None; ev = ""; stats = {}
    try:
        with urllib.request.urlopen(urllib.request.Request(a.url + "/api/chat", body, {"Content-Type": "application/json"}), timeout=900) as r:
            for line in r:
                line = line.decode("utf-8", "replace").strip()
                if line.startswith("event:"): ev = line[6:].strip()
                elif line.startswith("data:"):
                    d = json.loads(line[5:])
                    if ev == "token" and isinstance(d, str) and d:
                        if first is None: first = time.time() - t0  # first visible text (the guards release text per sentence)
                    elif ev == "done": stats = d
                    elif ev == "error": err = d.get("error", "error")
    except Exception as e:
        err = f"{type(e).__name__}: {e}"
    return first, stats.get("tokens", 0), (stats.get("tok_per_s") or None), err  # tokens and tok/s come from llama-server's own timings in the 'done' event


def run(users):
    res = []; lock = threading.Lock(); start = threading.Barrier(users)

    def learner(u):
        start.wait()
        for i in range(a.requests):
            r = chat(QUESTIONS[(u * 3 + i) % len(QUESTIONS)])
            with lock: res.append(r)
    t0 = time.time(); th = [threading.Thread(target=learner, args=(u,)) for u in range(users)]
    [t.start() for t in th]; [t.join() for t in th]
    wall = time.time() - t0
    ok = [r for r in res if not r[3] and r[0] is not None]
    pc = lambda xs, p: sorted(xs)[min(len(xs) - 1, int(p * len(xs)))] if xs else float("nan")
    ttft = [r[0] for r in ok]; tps = [r[2] for r in ok if r[2]]
    return dict(users=users, requests=len(res), errors=len([r for r in res if r[3]]), p50=pc(ttft, .5), p95=pc(ttft, .95),
                tps_p50=statistics.median(tps) if tps else float("nan"), tps_min=min(tps) if tps else float("nan"), total_tps=sum(r[1] for r in ok) / wall, wall=wall,
                first_error=next((r[3] for r in res if r[3]), ""))


rows = []
if a.warmup:
    chat(QUESTIONS[0]); print("warm-up request done (not recorded)")
print(f"{len(QUESTIONS)} questions in rotation, {a.requests} requests per learner, app {a.url}")
for n in [int(x) for x in a.users.split(",")]:
    print(f"running {n} learner(s)...", flush=True)
    r = run(n); rows.append(r)
    print(f"  p50 first token {r['p50']:.1f}s  p95 {r['p95']:.1f}s  per-stream {r['tps_p50']:.1f} tok/s (min {r['tps_min']:.1f})  total {r['total_tps']:.1f} tok/s  errors {r['errors']}")
L = [f"# Concurrency test, {datetime.date.today().isoformat()}", "",
     f"{len(QUESTIONS)} questions in a fixed rotation (mixed types), {a.requests} requests per learner, all learners start together. Time to first token is measured from sending the request, so it includes waiting for a free slot. Tokens/s is the server-measured generation speed of each stream (the 'done' event); total tok/s is generated tokens over wall time. A reply the app writes itself (Filipino check) has no model tokens and is left out of the speed columns.", "",
     "| learners | requests | errors | first token p50 (s) | first token p95 (s) | per-stream tok/s p50 | per-stream tok/s min | total tok/s | wall (s) |", "|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    L.append(f"| {r['users']} | {r['requests']} | {r['errors']} | {r['p50']:.1f} | {r['p95']:.1f} | {r['tps_p50']:.1f} | {r['tps_min']:.1f} | {r['total_tps']:.1f} | {r['wall']:.0f} |")
errs = [r["first_error"] for r in rows if r["first_error"]]
if errs: L += ["", "First error seen: " + errs[0]]
five = next((r for r in rows if r["users"] == 5), None)
if five: L += ["", f"Target at 5 learners: p95 first token <= 10 s and every stream >= 8 tok/s -> **{'MET' if five['p95'] <= 10 and five['tps_min'] >= 8 and not five['errors'] else 'NOT MET'}**"]
if a.json: json.dump(rows, open(a.json, "w", encoding="utf-8"), indent=1)
os.makedirs(os.path.join(config.HERE, "results"), exist_ok=True)
path = os.path.join(config.HERE, "results", f"concurrency-{datetime.date.today().isoformat()}.md")
open(path, "w", encoding="utf-8").write("\n".join(L) + "\n"); print("\n".join(L)); print("\nwritten:", path)
