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


class FakeServer:
    asked = []

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


if __name__ == "__main__":
    unittest.main()
