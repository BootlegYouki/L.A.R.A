# Pluggable Socratic AI & SLM Evaluation Guardrails

This rule document governs all on-device and Hub-assisted Small Language Model (SLM) operations for **L.A.R.A AI**.

All AI agents and contributors must follow these rules.

---

## 1. Pluggable Architecture & Experimental Model Benchmarking

The AI inference runtime is strictly **model-agnostic and pluggable**, built on the standard **GGUF format and `llama.cpp` / `llama-server` runtime**. 

Because public school hardware and student comprehension requirements vary, the project conducts experimental comparative benchmarking across candidate sub-3B Small Language Models (SLMs) to determine the best balance of pedagogical reasoning, bilingual Filipino/English fluency, memory footprint, and CPU inference speed:

### Candidate SLM Evaluation Matrix
* **Primary Baseline Candidate:** **MiniCPM5-2B (Int4 / Q4_K_M GGUF, ~1.55GB)** — High multimodal and bilingual capability.
* **Alternative Experimental Candidates:**
  * **Qwen2.5-1.5B / 3B (Instruct GGUF)** — Exceptional reasoning density and multilingual instruction following.
  * **Llama-3.2-1B / 3B (Instruct GGUF)** — Extremely lightweight edge runtime with high token throughput on budget CPUs.
  * **SmolLM2-1.7B (Instruct GGUF)** — Minimal memory overhead tailored for resource-constrained edge devices.
  * **Gemma-2-2B (IT GGUF)** — Strong factual grounding and textbook reasoning.
  * **Phi-3.5-mini-3.8B (GGUF)** — Superior Socratic mathematical and logical deduction.

### Technical Invariant
* **Zero Code Changes for Model Swapping:** Client apps and Local Hub must load models via dynamic configuration (`MODEL_PATH=models/*.gguf`). Changing from MiniCPM5-2B to Qwen2.5 or Llama-3.2 must only require pointing to the target GGUF file without altering JNI bindings or WebSocket streaming logic.
* **Context Window Standard:** All candidate models are constrained to a context window of **2,048 tokens** to minimize KV-cache RAM allocations and latency.

---

## 2. Hardware RAM Thresholding (The Crash Prevention Rule)

Over 50% of Filipino student smartphones are 3GB/4GB RAM entry-level devices (Infinix, TECNO, realme). Android OS consumes ~1.8GB to 2.2GB, leaving only ~800MB–1.2GB usable RAM. Attempting to load a 1.5GB+ model locally will trigger an instant Android Out-Of-Memory (OOM) crash.

### Strict Execution Logic
1. **On-App Launch:** The client must check total physical RAM via `ActivityManager.getMemoryInfo().totalMem`.
2. **If Physical RAM < 6GB:**
   * **Must strictly route to Hub-Assisted Mode.**
   * Streams tokens from the Local Hub over WebSockets (`ws://<hub-ip>:8081/api/ai/chat`).
   * App heap memory must remain **strictly < 250MB**.
3. **If Physical RAM >= 6GB or Laptop/Desktop:**
   * If a supported active GGUF model exists in local storage: Execute 100% locally via `llama.cpp` (JNI on Android, sidecar binary on Desktop).
   * If not downloaded: Offer Wi-Fi download from captive portal, defaulting to Hub stream.


---

## 3. Strict Socratic Pedagogical Behavior

L.A.R.A AI is a mentor for elementary pupils (Grades 1 to 6), not an answer engine.

### Non-Negotiable Directives:
1. **Zero Direct Answers:** Under no circumstances should the model output the final solution, answer key, or complete homework answers.
2. **Polite Refusal Template:** If asked "What is the answer to #3?" or "Ano ang sagot sa tanong na ito?", respond warmly:
   *"Hindi ko maibibigay ang mismong sagot, pero tutulungan kitang tuklasin ito! Balikan natin ang binasa mo. Ano ang napansin mo sa unang bahagi?"*
3. **Document Anchoring:** The prompt must bind the pre-extracted text chunks of the active lesson document. All hints must reference concepts directly from the teacher's handout.
4. **Step-by-Step Questioning:** Give only ONE small clue at a time, followed by a leading question prompting the child to take the next step.
5. **Bilingual Agility:** Automatically detect and reply in the student's selected language (English or natural conversational Filipino/Taglish).

---

## 4. Hub FIFO Inference Queue

To prevent the teacher's laptop from overloading when multiple low-RAM devices ask questions simultaneously:
* Configure `llama-server` with **2 to 4 parallel inference slots**.
* Additional requests enter a **FIFO Queue**.
* Push real-time queue position updates over WebSockets: *"Nag-iisip si L.A.R.A AI... Pangalawa ka sa pila (~4s)"*.
