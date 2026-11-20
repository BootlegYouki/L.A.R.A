# Model comparison — RX 570 experiment

All listed measurements came from this experiment. Quality scores are automated flags from the 65-prompt sample lesson run with V5; they are weak proxies and manual inspection found missed answer leakage. The new Wells PDF comparison used the verified 30-entry key and is reported separately in `REPORT.md`.

Speed requirement for five learners is conjunctive: p95 first token <=10 s **and** every stream >=8 tok/s, with no errors. None of the tested models met both. A p95-only result is not a pass.

| model | load | load s | peak VRAM GB | pp512 | pp1024 | tg128 | 5-user p95 s | 5-user min tok/s | automated right page % | automated leak % | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Qwen3-4B-Instruct-2507 Q4_K_M | yes | 4 | 3.8 | 192 | 186 | 21.5 | 26.2 | 1.2 | 90.0 | 0.0 | baseline; 37/37 layers GPU |
| Gemma-3-4B-it Q4_K_M | yes | 7 | 3.4 | 229 | 171 | 19.1 | 8.8 | 1.4 | 90.0 | 0.0 | p95 passed alone; stream threshold failed |
| Phi-4-mini-instruct Q4_K_M | yes | 5 | 3.6 | 228 | 213 | 25.1 | 35.7 | 0.5 | 92.5 | 0.0 | 33/33 layers GPU |
| Qwen3-8B Q4_K_M | yes | 6 | 5.9 | 111 | 109 | 17.2 | 64.6 | 0.3 | 92.5 | 0.0 | 37/37 layers GPU |
| Granite-4.0-H-Tiny Q4_K_M | yes | 6 | 4.1 | 319 | 321 | 11.9 | 55.0 | 0.7 | 90.0 | 0.0 | 41/41 layers GPU |
| LFM2-8B-A1B Q4_K_M | yes | 6 | 4.9 | 354 | 351 | 28.9 | 17.3 | 3.2 | 90.0 | 0.0 | 25/25 layers GPU |
| Qwen3-30B-A3B REAP-15B, all experts CPU | yes | 6 | 1.8 | not measured | not measured | not measured | 78.2 | 0.4 | 90.0 | 0.0 | 49/49 layers on Vulkan; paging avg 68.8, max 6802 pages/s |
| Qwen3-30B-A3B REAP-15B, 21 layers experts CPU | yes | 36 | 6.4 | not measured | not measured | not measured | 33.2 | 0.5 | 90.0 | 0.0 | 49/49 layers on Vulkan; paging avg 540.8, max 34892.9 pages/s |
| Original Qwen3-30B-A3B | not run | — | — | — | — | — | — | — | — | — | not downloaded: 17.28 GiB file vs 22.14 GiB available RAM left only 4.86 GiB before runtime/OS headroom; REAP trials showed paging |

The optional model comparison used 4 requests per learner at 1, 3 and 5 users; the primary baseline and optimization runs used 6 requests per learner at 1, 2, 3, 5 and 8 users. Do not compare these concurrency samples as if they used the same workload. All tested models had zero request errors in their recorded samples.

Automated sample-prompt “0% leak” does not mean the models avoided giving answers. For example, manual inspection found answer-revealing outputs from Gemma V5 and other candidates despite zero automated leak flags. The benchmark’s own “best model” labels used a p95-only speed gate, which is superseded here by the required p95 plus per-stream criterion. No model qualifies as the five-learner winner. Quality ranking is not defensible from the automated score alone.

### Runtime notes

- llama.cpp 0.7.0-dev, build 11938, commit 19cce6919; Vulkan driver 31.0.21923.11000.
- RX 570 dedicated memory: 8192 MiB. Vulkan server detected Radeon RX 570 Series; baseline offloaded 37/37 layers.
- RAM: 31.9 GiB installed. Before the original 30B decision, 22.14 GiB was available; no original 30B download was attempted.
- The benchmark script reported model storage as HDD, while an independent probe returned unknown; drive type is therefore unverified.
- The `--no-mmap` option is not supported by this llama.cpp build. The successful retry used `--load-mode none`. The failed command is retained as an experiment error in raw local logs, which are not committed.