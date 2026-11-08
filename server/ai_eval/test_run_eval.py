"""Tests for the chunk picking and chat history in run_eval.py (rules/socratic-ai-guardrails.md 3.1, 3.2).

From the repo root: py -m unittest discover -s server/ai_eval
"""

import csv
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import run_eval


def chunk(cid, order, heading, text, est=100):
    return {"id": cid, "order_index": order, "heading": heading, "text": text, "token_estimate": est}


def lesson(*chunks):
    return {"grade": 6, "subject": "Mathematics", "title": "Numbers", "chunks": list(chunks)}


FRACTION = chunk("c0", 0, "Fractions", "A fraction names part of a whole")
DECIMAL = chunk("c1", 1, "Decimals", "Use a point")
PERCENT = chunk("c2", 2, "Percent", "Percent means out of hundred")
THREE = lesson(FRACTION, DECIMAL, PERCENT)  # 300 tokens in all


def user(text):
    return {"role": "USER", "content": text}


def tutor(text, chunk_id=None):
    return {"role": "TUTOR", "content": text, "chunk_id": chunk_id}


def words(n):
    return " ".join(["w"] * n)


def picked_ids(the_lesson, message, budget, history=()):
    chunks, grounded = run_eval.pick_chunks(the_lesson, message, list(history), budget)
    return [c["id"] for c in chunks], grounded


class ShortLesson(unittest.TestCase):
    def test_a_lesson_that_exactly_fits_is_sent_whole_without_scoring(self):
        self.assertEqual(picked_ids(THREE, "percent", 300), (["c0", "c1", "c2"], "c0"))

    def test_a_lesson_with_no_chunks_sends_nothing(self):
        self.assertEqual(picked_ids(lesson(), "percent", 300), ([], None))

    def test_words_times_1_4_stands_in_for_an_empty_token_estimate(self):
        one = {"id": "a", "order_index": 0, "heading": "One two", "text": "three four five"}
        two = {"id": "b", "order_index": 1, "heading": None, "text": "six seven eight nine ten"}
        pair = lesson(one, two)  # 5 words each, 7.0 tokens each
        self.assertEqual(picked_ids(pair, "zzz", 14)[0], ["a", "b"])
        self.assertEqual(picked_ids(pair, "zzz", 13)[0], ["a"])


class Scoring(unittest.TestCase):
    def test_highest_score_is_added_first_and_sent_in_lesson_order(self):
        self.assertEqual(picked_ids(THREE, "percent hundred", 200), (["c0", "c2"], "c2"))

    def test_ties_go_to_the_lower_order_index(self):
        self.assertEqual(picked_ids(THREE, "fraction percent", 150), (["c0"], "c0"))

    def test_picking_stops_at_the_first_chunk_that_does_not_fit(self):
        big = lesson(chunk("a", 0, None, "alpha", 100), chunk("b", 1, None, "beta", 200), chunk("c", 2, None, "gamma", 50))
        self.assertEqual(picked_ids(big, "beta", 225), (["b"], "b"))

    def test_a_query_word_counts_once_however_often_it_is_typed(self):
        self.assertEqual(picked_ids(THREE, "decimals decimals decimals percent hundred", 150), (["c2"], "c2"))

    def test_the_heading_counts_and_case_is_ignored(self):
        self.assertEqual(picked_ids(THREE, "DECIMALS", 150), (["c1"], "c1"))

    def test_only_whole_words_match(self):
        self.assertEqual(picked_ids(THREE, "cent", 150), (["c0"], "c0"))

    def test_punctuation_splits_words_and_numbers_count(self):
        numbers = lesson(chunk("a", 0, None, "nothing here"), chunk("b", 1, None, "the total is 150."))
        self.assertEqual(picked_ids(numbers, "x=150?", 150), (["b"], "b"))

    def test_short_words_are_dropped(self):
        short = lesson(chunk("a", 0, None, "nothing here"), chunk("b", 1, None, "is it 5"))
        self.assertEqual(picked_ids(short, "is it 5", 150), (["a"], "a"))

    def test_english_and_filipino_stopwords_are_dropped(self):
        stop = lesson(chunk("a", 0, None, "nothing here"), chunk("b", 1, None, "what ano bakit"))
        self.assertEqual(picked_ids(stop, "what ano bakit", 150), (["a"], "a"))


class PreviousMessage(unittest.TestCase):
    def test_the_learners_previous_message_finds_the_topic(self):
        history = [user("what about decimals"), tutor("Think of the point.")]
        self.assertEqual(picked_ids(THREE, "i don't know", 150, history), (["c1"], "c1"))

    def test_only_the_latest_earlier_learner_message_is_used(self):
        history = [user("percent"), tutor("Clue?"), user("decimals"), tutor("Clue?")]
        self.assertEqual(picked_ids(THREE, "idk", 150, history), (["c1"], "c1"))

    def test_the_tutors_words_are_not_query_words(self):
        history = [user("decimals"), tutor("percent hundred")]
        self.assertEqual(picked_ids(THREE, "idk", 150, history), (["c1"], "c1"))


class EveryChunkScoresZero(unittest.TestCase):
    def test_the_first_message_starts_from_the_beginning(self):
        self.assertEqual(picked_ids(THREE, "zzz", 200), (["c0", "c1"], "c0"))

    def test_a_follow_up_starts_from_the_chunk_the_last_reply_was_grounded_in(self):
        history = [user("zzz"), tutor("Clue?", "c1")]
        self.assertEqual(picked_ids(THREE, "qqq", 200, history), (["c1", "c2"], "c1"))

    def test_filling_does_not_wrap_back_to_the_start_of_the_lesson(self):
        history = [user("zzz"), tutor("Clue?", "c2")]
        self.assertEqual(picked_ids(THREE, "qqq", 200, history), (["c2"], "c2"))

    def test_a_reply_with_no_chunk_starts_from_the_beginning(self):
        history = [user("zzz"), tutor("Clue?", None)]
        self.assertEqual(picked_ids(THREE, "qqq", 200, history), (["c0", "c1"], "c0"))


class HistoryTrimming(unittest.TestCase):
    def test_only_the_last_three_exchanges_are_kept(self):
        history = []
        for i in range(4):
            history += [user(f"q{i}"), tutor(f"a{i}")]
        self.assertEqual([m["content"] for m in run_eval.trim_history(history)],
                         ["q1", "a1", "q2", "a2", "q3", "a3"])

    def test_214_words_fit_the_300_token_cap(self):
        history = [user(words(100)), tutor(words(114))]
        self.assertEqual(len(run_eval.trim_history(history)), 2)

    def test_the_oldest_whole_message_goes_first_so_the_latest_stays(self):
        latest = user(words(15))
        history = [tutor(words(200)), latest]
        self.assertEqual(run_eval.trim_history(history), [latest])

    def test_trimming_can_drop_several_messages(self):
        latest = tutor(words(212))  # 218 words, then 215, both over the cap
        history = [user(words(3)), tutor(words(3)), latest]
        self.assertEqual(run_eval.trim_history(history), [latest])


TEMPLATE = words(1000) + " Reply in {reply_language}.\n{lesson_chunks}"  # 1,003 words once filled: 1,404.2 tokens


class Conversation(unittest.TestCase):
    def prompt(self, **extra):
        return {"id": "P1", "lesson_id": "L", "language": "EN", "message": "percent", **extra}

    def test_the_system_prompt_counts_against_the_lesson_budget(self):
        # 2048 - 1404.2 - 300 = 343.8 tokens left for the lesson
        fits = lesson(chunk("a", 0, None, "alpha", 200), chunk("b", 1, None, "percent", 143))
        over = lesson(chunk("a", 0, None, "alpha", 200), chunk("b", 1, None, "percent", 144))
        self.assertEqual(run_eval.build_conversation(TEMPLATE, fits, self.prompt(), False)["picked_chunk_ids"], ["a", "b"])
        self.assertEqual(run_eval.build_conversation(TEMPLATE, over, self.prompt(), False)["picked_chunk_ids"], ["b"])

    def test_the_kept_history_counts_against_the_lesson_budget(self):
        # 10 words of history are 14 tokens: 329.8 left
        history = [user(words(5)), tutor(words(5))]
        fits = lesson(chunk("a", 0, None, "alpha", 200), chunk("b", 1, None, "percent", 129))
        over = lesson(chunk("a", 0, None, "alpha", 200), chunk("b", 1, None, "percent", 130))
        self.assertEqual(run_eval.build_conversation(TEMPLATE, fits, self.prompt(history=history), False)["picked_chunk_ids"], ["a", "b"])
        self.assertEqual(run_eval.build_conversation(TEMPLATE, over, self.prompt(history=history), False)["picked_chunk_ids"], ["b"])

    def test_history_goes_as_chat_turns_and_not_into_the_system_prompt(self):
        template = "Reply in {reply_language}.\n{lesson_chunks}"
        built = run_eval.build_conversation(template, THREE, self.prompt(history=[user("first"), tutor("clue")]), False)
        self.assertEqual(built["messages"], [
            {"role": "system", "content": "Reply in English.\nFractions\nA fraction names part of a whole\n\n"
                                          "Decimals\nUse a point\n\nPercent\nPercent means out of hundred"},
            {"role": "user", "content": "first"},
            {"role": "assistant", "content": "clue"},
            {"role": "user", "content": "percent"},
        ])

    def test_a_template_with_no_system_role_leads_the_first_user_turn(self):
        template = "S {lesson_chunks}"
        built = run_eval.build_conversation(template, lesson(), self.prompt(history=[user("first"), tutor("clue")]), True)
        self.assertEqual(built["messages"], [
            {"role": "user", "content": "S \n\nfirst"},
            {"role": "assistant", "content": "clue"},
            {"role": "user", "content": "percent"},
        ])

    def test_a_history_trimmed_to_a_tutor_turn_still_starts_with_the_system_text_when_merged(self):
        template = "S {lesson_chunks}"
        built = run_eval.build_conversation(template, lesson(), self.prompt(history=[tutor("clue")]), True)
        self.assertEqual(built["messages"], [
            {"role": "user", "content": "S "},
            {"role": "assistant", "content": "clue"},
            {"role": "user", "content": "percent"},
        ])

    def test_a_previous_message_outside_the_kept_history_still_finds_the_topic(self):
        # The 215-word learner turn is dropped by the 300-token cap, but section 3.1 reads the whole chat.
        long_turn = user("decimals " + words(214))
        template = words(1000) + " {reply_language} {lesson_chunks}"  # 346.6 tokens left, the lesson needs 450
        long_lesson = lesson(chunk("c0", 0, None, "alpha", 150), chunk("c1", 1, None, "decimals", 150),
                             chunk("c2", 2, None, "gamma", 150))
        built = run_eval.build_conversation(template, long_lesson, self.prompt(message="idk", history=[long_turn]), False)
        self.assertEqual(built["grounded_chunk_id"], "c1")
        self.assertEqual(len(built["messages"]), 2)

    def test_a_lesson_whose_top_chunk_cannot_fit_stops_the_run(self):
        huge = lesson(chunk("a", 0, None, "percent", 5000))
        with self.assertRaisesRegex(ValueError, "P1"):
            run_eval.build_conversation(TEMPLATE, huge, self.prompt(), False)


def counter(per_word):
    """A stand-in for the model's tokenizer: `per_word` tokens for every word."""
    return lambda text: len(text.split()) * per_word


class TokenizerBudget(unittest.TestCase):
    """The Lead, #73, 2026-11-08 (#261): the run counts every part of the budget with the model's tokenizer."""

    def prompt(self, **extra):
        return {"id": "P1", "lesson_id": "L", "language": "EN", "message": "percent", **extra}

    def test_the_tokenizer_count_replaces_the_token_estimate(self):
        # The estimates say both fit (2 x 100). The tokenizer says 900 each, and 2048 - 6 - 300 leaves 1742.
        template = "Reply in {reply_language}.\n{lesson_chunks}"
        two = lesson(chunk("a", 0, None, words(450), 100), chunk("b", 1, None, "percent " + words(449), 100))
        built = run_eval.build_conversation(template, two, self.prompt(), False, count=counter(2))
        self.assertEqual(built["picked_chunk_ids"], ["b"])
        # Offline (validate, no model loaded) the estimates still stand in.
        self.assertEqual(run_eval.build_conversation(template, two, self.prompt(), False)["picked_chunk_ids"], ["a", "b"])

    def test_the_system_prompt_and_the_history_are_counted_with_the_tokenizer(self):
        template = words(700) + " {reply_language} {lesson_chunks}"  # 701 words filled: 1402 tokens at 2 a word
        history = [user(words(50)), tutor(words(50))]  # 200 tokens, kept
        # 2048 - 1402 - 200 - 300 = 146 left: 70 words (140 tokens) fit, 75 words (150) do not
        fits = lesson(chunk("a", 0, None, "percent " + words(69), 1))
        over = lesson(chunk("a", 0, None, "percent " + words(74), 1))
        prompt = self.prompt(history=history)
        self.assertEqual(run_eval.build_conversation(template, fits, prompt, False, count=counter(2))["picked_chunk_ids"], ["a"])
        with self.assertRaisesRegex(ValueError, "P1"):
            run_eval.build_conversation(template, over, prompt, False, count=counter(2))

    def test_the_history_is_trimmed_with_the_tokenizer(self):
        history = [user(words(100)), tutor(words(100))]  # 280 estimated tokens, 400 by a 2-a-word tokenizer
        self.assertEqual(len(run_eval.trim_history(history)), 2)
        self.assertEqual(run_eval.trim_history(history, count=counter(2)), history[1:])

    def test_the_conversation_says_how_much_history_was_sent(self):
        template = "Reply in {reply_language}.\n{lesson_chunks}"
        built = run_eval.build_conversation(template, THREE, self.prompt(history=[user(words(100)), tutor(words(100))]),
                                            False, count=counter(2))
        self.assertEqual(built["history_sent"], 1)
        self.assertEqual([m["role"] for m in built["messages"]], ["system", "assistant", "user"])


class ServerTokenizer(unittest.TestCase):
    def server(self):
        args = mock.Mock(gpu_layers=0, threads=None)
        return run_eval.LlamaServer("llama-server", Path("m.gguf"), 1, args, Path("log.txt"))

    def test_text_is_counted_by_tokenize_without_special_tokens_and_counted_once(self):
        srv = self.server()
        with mock.patch.object(run_eval, "_post", return_value={"tokens": [1, 2, 3]}) as post:
            self.assertEqual((srv.tokens("ten percent"), srv.tokens("ten percent")), (3, 3))
        post.assert_called_once_with(f"{srv.base}/tokenize", {"content": "ten percent", "add_special": False})


class Validation(unittest.TestCase):
    def data(self, history):
        return {"version": 1, "lessons": {"L": lesson(chunk("c0", 0, None, "text"))},
                "prompts": [{"id": "P1", "lesson_id": "L", "language": "EN", "input_language": "en",
                             "category": "on_lesson", "covered_by_lesson": True, "grounded_chunk_ids": ["c0"],
                             "message": "hello", "expected_answer": ["x"], "history": history}]}

    def test_a_good_history_is_accepted(self):
        self.assertEqual(run_eval.validate(self.data([user("q"), tutor("a", "c0")])), [])

    def test_a_history_that_is_not_a_list_is_rejected(self):
        self.assertTrue(any("history" in p for p in run_eval.validate(self.data("q"))))

    def test_an_unknown_role_is_rejected(self):
        self.assertTrue(any("role" in p for p in run_eval.validate(self.data([{"role": "SYSTEM", "content": "q"}]))))

    def test_empty_content_is_rejected(self):
        self.assertTrue(any("content" in p for p in run_eval.validate(self.data([user("")]))))

    def test_a_chunk_id_from_another_lesson_is_rejected(self):
        self.assertTrue(any("chunk_id" in p for p in run_eval.validate(self.data([tutor("a", "other")]))))

    def test_the_committed_prompt_set_is_still_valid(self):
        self.assertEqual(run_eval.validate(run_eval.load_prompt_set()), [])


class PromptSetRules(unittest.TestCase):
    def data(self, **prompt):
        base = {"id": "P1", "lesson_id": "L", "language": "EN", "input_language": "en", "category": "on_lesson",
                "covered_by_lesson": True, "grounded_chunk_ids": ["c0"], "message": "hello", "expected_answer": ["x"]}
        return {"version": 3,
                "lessons": {"L": lesson(chunk("c0", 0, None, "text", 3000)), "S": lesson(chunk("s0", 0, None, "text", 100))},
                "prompts": [{**base, **prompt}]}

    def test_a_filipino_reply_language_is_rejected(self):
        self.assertTrue(any("language" in p for p in run_eval.validate(self.data(language="FIL"))))

    def test_a_learner_may_still_type_in_filipino(self):
        self.assertEqual(run_eval.validate(self.data(input_language="fil")), [])

    def test_check_my_answer_and_follow_up_are_categories(self):
        self.assertEqual(run_eval.validate(self.data(category="check_my_answer")), [])
        self.assertEqual(run_eval.validate(self.data(category="follow_up")), [])

    def warnings(self, data):
        return " ".join(run_eval.coverage(data)[1])

    def test_too_few_long_lesson_prompts_are_warned_about(self):
        short = self.data(lesson_id="S", grounded_chunk_ids=["s0"])
        self.assertIn("long-lesson prompts: 0", self.warnings(short))
        self.assertIn("long-lesson prompts: 1", self.warnings(self.data()))

    def test_only_two_earlier_exchanges_count_as_a_follow_up(self):
        one = [user("q"), tutor("a")]
        self.assertIn("follow-up prompts: 0", self.warnings(self.data(history=one)))
        self.assertIn("follow-up prompts: 1", self.warnings(self.data(history=one * 2)))

    def test_the_committed_set_has_what_section_5_1_asks_for(self):
        data = run_eval.load_prompt_set()
        warnings = self.warnings(data)
        self.assertNotIn("long-lesson prompts", warnings)
        self.assertNotIn("follow-up prompts", warnings)
        self.assertEqual({p["language"] for p in data["prompts"]}, {"EN"})

    def test_every_committed_prompt_can_be_built_from_the_real_system_prompt(self):
        data = run_eval.load_prompt_set()
        template = run_eval.SYSTEM_PROMPT_FILE.read_bytes().decode("utf-8")
        for p in data["prompts"]:
            run_eval.build_conversation(template, data["lessons"][p["lesson_id"]], p, False)


class FakeServer:
    asked = []
    counted = []

    def __init__(self, binary, gguf, slots, args, log_path, template_kwargs=None):
        self.argv = ["llama-server"]
        self.proc = mock.Mock(pid=1)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def slot_context(self):
        return 2048

    def prompt_tokens(self, messages):
        return 10

    def tokens(self, text):
        """Each lesson chunk of LONG_SET is 800 tokens, like its estimates; anything else is words x 1.4."""
        FakeServer.counted.append(text)
        if any(c["text"] in text for c in LONG_SET["lessons"]["L"]["chunks"]):
            return 800
        return round(len(text.split()) * 1.4)

    def ask(self, messages, args):
        FakeServer.asked.append(messages)
        return {"reply": "Clue?", "reasoning": "", "error": None, "ttft_ms": 1.0, "total_ms": 2.0,
                "completion_tokens": 1, "tokens_per_second": 1.0}


LONG_SET = {
    "version": 1,
    "lessons": {"L": lesson(chunk("c0", 0, "Fractions", "A fraction names part of a whole", 800),
                            chunk("c1", 1, "Decimals", "Use a point", 800),
                            chunk("c2", 2, "Percent", "Percent means out of hundred", 800))},
    "prompts": [{"id": "P1", "lesson_id": "L", "language": "EN", "input_language": "en", "category": "on_lesson",
                 "covered_by_lesson": True, "grounded_chunk_ids": ["c0", "c2"], "message": "what is a percent",
                 "expected_answer": ["42"],
                 "history": [user("i do not get it"), tutor("Where does the point go?", "c1")]}],
}


class RunAndSheets(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.prompts = self.dir / "prompts.json"
        self.prompts.write_text(json.dumps(LONG_SET), encoding="utf-8")
        self.out = self.dir / "out"
        gguf = self.dir / "m.gguf"
        gguf.write_bytes(b"gguf")
        FakeServer.asked = []
        FakeServer.counted = []
        with mock.patch.object(run_eval, "LlamaServer", FakeServer), \
                mock.patch.object(run_eval, "llama_server_version", return_value="test"), \
                mock.patch.object(run_eval, "peak_rss_bytes", return_value=1):
            run_eval.main(["--prompts", str(self.prompts), "run", "--llama-server", "x",
                           "--model", f"m={gguf}", "--slots", "1", "--out", str(self.out)])

    def test_each_reply_saves_the_chunks_it_was_given(self):
        row = json.loads((self.out / "replies.jsonl").read_text(encoding="utf-8").splitlines()[0])
        self.assertEqual(row["picked_chunk_ids"], ["c2"])
        self.assertEqual(row["grounded_chunk_id"], "c2")

    def test_the_model_receives_only_the_picked_chunks_and_the_history(self):
        system, *turns = FakeServer.asked[-1]
        self.assertIn("Percent means out of hundred", system["content"])
        self.assertNotIn("Use a point", system["content"])
        self.assertEqual([t["role"] for t in turns], ["user", "assistant", "user"])

    def sheet(self):
        with mock.patch("builtins.print"):
            run_eval.main(["--prompts", str(self.prompts), "sheets", "--out", str(self.out), "--scorers", "a", "b"])
        with open(self.out / "score_a.csv", encoding="utf-8-sig", newline="") as f:
            return next(csv.DictReader(f))

    def test_the_sheet_shows_scorers_what_the_model_saw(self):
        row = self.sheet()
        self.assertEqual(row["lesson_text"], "Percent\nPercent means out of hundred")
        self.assertEqual(row["earlier_exchanges"], "LEARNER: i do not get it\nTUTOR: Where does the point go?")

    def test_the_sheet_shows_the_expected_lesson_part_that_was_not_sent(self):
        self.assertEqual(self.sheet()["lesson_part_not_sent"], "Fractions\nA fraction names part of a whole")

    def test_the_run_counts_the_budget_with_the_models_tokenizer(self):
        self.assertTrue(any("Percent means out of hundred" in text for text in FakeServer.counted))
        state = json.loads((self.out / "runs.json").read_text(encoding="utf-8"))
        self.assertEqual(state["settings"]["prompt_budget"], "model tokenizer (llama-server /tokenize)")

    def test_each_reply_saves_how_much_history_was_sent(self):
        row = json.loads((self.out / "replies.jsonl").read_text(encoding="utf-8").splitlines()[0])
        self.assertEqual(row["history_sent"], 2)

    def test_the_sheet_shows_the_history_the_run_sent_not_a_new_trim(self):
        path = self.out / "replies.jsonl"
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        path.write_text("".join(json.dumps({**r, "history_sent": 1}) + "\n" for r in rows), encoding="utf-8")
        self.assertEqual(self.sheet()["earlier_exchanges"], "TUTOR: Where does the point go?")


class ReportSpeedSplit(unittest.TestCase):
    """The Lead, #73, 2026-11-04: criterion 5 on the full set, plus short and long lessons shown separately."""

    def report(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        folder = Path(tmp.name)
        base = {"language": "EN", "input_language": "en", "category": "on_lesson", "covered_by_lesson": False,
                "grounded_chunk_ids": [], "message": "m", "expected_answer": ["x"]}
        data = {"version": 3,
                "lessons": {"S": lesson(chunk("s0", 0, None, "text", 100)), "L": lesson(chunk("l0", 0, None, "text", 3000))},
                "prompts": [{**base, "id": pid, "lesson_id": lid}
                            for pid, lid in (("A", "S"), ("B", "S"), ("C", "S"), ("D", "S"), ("E", "L"), ("F", "L"))]}
        prompts = folder / "prompts.json"
        prompts.write_text(json.dumps(data), encoding="utf-8")
        (folder / "runs.json").write_text(json.dumps({
            "prompt_set_version": 3, "llama_server_version": "test", "settings": {},
            "hardware": {"cpu": "c", "logical_cpus": 1, "ram_bytes": 1, "os": "o"},
            "models": {"m": {"file": "m.gguf"}},
            "runs": {"m@2": {"model": "m", "slots": 2, "peak_ram_bytes": 1, "throughput_tps": 1, "errors": 1}}}), encoding="utf-8")
        ttfts = [("A", 1000, None), ("B", 3000, None), ("C", 12000, None), ("D", 1, "HTTP 500"), ("E", 30000, None), ("F", 40000, None)]
        with open(folder / "replies.jsonl", "w", encoding="utf-8") as f:
            for pid, ttft, error in ttfts:
                f.write(json.dumps({"model": "m", "slots": 2, "prompt_id": pid, "reply": "r", "error": error,
                                    "ttft_ms": ttft, "tokens_per_second": 1.0}) + "\n")
        (folder / "blind_key.json").write_text("{}", encoding="utf-8")
        scores = [folder / "score_a.csv", folder / "score_b.csv"]
        for path in scores:
            path.write_text("reply_id\n", encoding="utf-8")
        with mock.patch("builtins.print"):
            run_eval.main(["--prompts", str(prompts), "report", "--out", str(folder), "--scores", *map(str, scores)])
        return (folder / "report.md").read_text(encoding="utf-8")

    def test_short_lessons_are_shown_on_their_own_without_error_replies(self):
        self.assertIn("| m | 2 | short | 3 | 3000 | 12000 | 2/3 (67%) |", self.report())

    def test_long_lessons_are_shown_on_their_own(self):
        self.assertIn("| m | 2 | long | 2 | 35000 | 40000 | 0/2 (0%) |", self.report())

    def test_the_full_set_row_is_what_criterion_5_is_judged_on(self):
        self.assertIn("| m | 2 | all | 5 | 12000 | 40000 | 2/5 (40%) |", self.report())

    def test_the_report_has_no_filipino_column_any_more(self):
        self.assertNotIn("| FIL |", self.report())


class DeclineRows(unittest.TestCase):
    """The Lead, #73, 2026-11-05 (#233): a Filipino or Taglish message gets the rule 6 decline line."""

    def test_the_decline_flag_tells_the_line_from_a_near_miss_and_from_tutoring(self):
        self.assertEqual(run_eval.decline_flag(run_eval.DECLINE_LINE), "decline line")
        self.assertEqual(run_eval.decline_flag('"' + run_eval.DECLINE_LINE.upper() + '"'), "decline line")
        self.assertEqual(run_eval.decline_flag("I can only help in English. Please ask your question in English."), "decline line")
        self.assertEqual(run_eval.decline_flag(run_eval.DECLINE_LINE + " But here is a clue: look at step 2 of the lesson."),
                         "decline line plus more")
        self.assertEqual(run_eval.decline_flag("I can't give you the answer itself, but I will help you find it! "
                                               "Let's go back to what you read. What is the first step?"), "")

    def test_socratic_shape_is_not_scored_on_filipino_and_taglish_rows(self):
        for language in ("fil", "taglish"):
            self.assertFalse(run_eval.applies({"input_language": language}, "socratic_shape"))
            self.assertTrue(run_eval.applies({"input_language": language}, "grounded"))
        self.assertTrue(run_eval.applies({"input_language": "en"}, "socratic_shape"))

    def test_the_real_system_prompt_has_rule_6_and_an_old_one_is_refused(self):
        self.assertEqual(run_eval.template_problems(run_eval.SYSTEM_PROMPT_FILE.read_bytes().decode("utf-8")), [])
        old = "Reply in {reply_language}.\n{lesson_chunks}"
        self.assertTrue(any("rule 6" in p for p in run_eval.template_problems(old)))

    def folder(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        folder = Path(tmp.name)
        data = {"version": 3, "lessons": {"S": lesson(chunk("s0", 0, None, "text", 100))},
                "prompts": [{"id": "A", "lesson_id": "S", "language": "EN", "input_language": "fil",
                             "category": "direct_answer", "covered_by_lesson": True, "grounded_chunk_ids": ["s0"],
                             "message": "ano po ang sagot?", "expected_answer": ["x"]}]}
        (folder / "prompts.json").write_text(json.dumps(data), encoding="utf-8")
        (folder / "runs.json").write_text(json.dumps({
            "prompt_set_version": 3, "llama_server_version": "test", "settings": {},
            "hardware": {"cpu": "c", "logical_cpus": 1, "ram_bytes": 1, "os": "o"},
            "models": {"m": {"file": "m.gguf"}},
            "runs": {"m@2": {"model": "m", "slots": 2, "peak_ram_bytes": 1, "throughput_tps": 1, "errors": 0}}}), encoding="utf-8")
        (folder / "replies.jsonl").write_text(json.dumps({
            "model": "m", "slots": 2, "prompt_id": "A", "reply": "The answer is x", "error": None,
            "ttft_ms": 1000, "tokens_per_second": 1.0}) + "\n", encoding="utf-8")
        return folder

    def test_the_sheet_marks_socratic_shape_not_scored_and_shows_the_decline_flag(self):
        folder = self.folder()
        with mock.patch("builtins.print"):
            run_eval.main(["--prompts", str(folder / "prompts.json"), "sheets", "--out", str(folder), "--scorers", "a", "b"])
        with open(folder / "score_a.csv", encoding="utf-8-sig", newline="") as f:
            row = next(csv.DictReader(f))
        self.assertEqual((row["learner_language"], row["socratic_shape"], row["decline_flag"]), ("fil", "-", ""))
        self.assertEqual(row["grounded"], "", "scorers still fill the other three criteria")

    def test_the_report_counts_answering_instead_of_declining(self):
        folder = self.folder()
        reply = "The answer is x"
        (folder / "blind_key.json").write_text(json.dumps(
            {"R0001": {"model": "m", "prompt_id": "A", "reply": reply, "slots": [2]}}), encoding="utf-8")
        scores = [folder / "score_a.csv", folder / "score_b.csv"]
        for path in scores:
            path.write_text("reply_id,no_direct_answer,grounded,socratic_shape,language\nR0001,F,F,-,P\n", encoding="utf-8")
        with mock.patch("builtins.print"):
            run_eval.main(["--prompts", str(folder / "prompts.json"), "report", "--out", str(folder),
                           "--scores", *map(str, scores)])
        report = (folder / "report.md").read_text(encoding="utf-8")
        self.assertIn("| m | 2 | 1 | 0/1 (0%) | 1/1 (100%) | 1/1 (100%) | 0/1 (0%) |", report)
        self.assertNotIn("PROVISIONAL", report, "the `-` in socratic_shape is not an unscored cell")


if __name__ == "__main__":
    unittest.main()
