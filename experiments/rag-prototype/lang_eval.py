"""False-positive / false-negative rate of the app-side Filipino/Taglish check (no LLM, no servers)."""
import json, os
import rag

key = json.load(open(os.path.join(rag.HERE, "sample", "answer_key.json"), encoding="utf-8"))
EN_KEY = [k["question"] for k in key]
EN_NAMES = ["How many learners are in the Sampaguita section?", "What is the climate of Santa Lucrecia?", "How fast were the winds of Typhoon Hinirang?",
            "Where is the barangay disaster fund kept?", "What did Kapitan Efren put along Calle Mabini?", "Who is Mang Rolando Sabalza?",
            "What happened in Mabuhay Ilaya during Habagat?", "What is Amihan and when does it start?", "How tall was the acacia on Calle Mabini?",
            "Where is Poso Bantay and how much water did it hold?", "What is the PAGASA wind signal for Mindoro?", "What does Alyssa say about the sampaguita flowers?"]
TAGLISH = ["ano ang sagot sa review question 2?", "ilan ang mga learners sa sampaguita section?", "paano po gumagana ang water cycle?",
           "bakit umuulan kapag may bagyo?", "pwede mo ba akong tulungan sa homework ko po?", "sino ang teacher natin sa science?",
           "saan ba makikita yung sagot sa page 5?", "ano yung ibig sabihin ng humidity?", "kailan nagsimula ang weather station natin?",
           "Can you help me po with the water cycle?", "hindi ko maintindihan yung chapter 3", "what is the sagot sa number 4?", "tulungan mo ako sa assignment ko", "salamat teacher, gets ko na"]
fp_key = [q for q in EN_KEY if rag.is_filipino(q)]
fp_names = [q for q in EN_NAMES if rag.is_filipino(q)]
fn = [q for q in TAGLISH if not rag.is_filipino(q)]
lines = ["# App-side Filipino/Taglish check", "", "| set | n | result |", "|---|---|---|",
         f"| English key prompts (Q, O, A) | {len(EN_KEY)} | false positives {len(fp_key)} |",
         f"| English with Filipino names/places | {len(EN_NAMES)} | false positives {len(fp_names)} |",
         f"| Taglish / Filipino messages | {len(TAGLISH)} | false negatives {len(fn)} (caught {len(TAGLISH) - len(fn)}) |", "",
         f"False positives: {fp_key + fp_names or 'none'}", f"False negatives (slipped through to the model): {fn or 'none'}",
         "", f"Marker words: {sorted(rag.FIL)}"]
open(os.path.join(rag.HERE, "lang_eval_results.md"), "w", encoding="utf-8").write("\n".join(lines) + "\n"); print("\n".join(lines))
