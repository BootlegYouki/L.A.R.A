package org.lara.experiment.rag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin port must give what the desktop rag.py gives (expected values written by tools/parity_reference.py). */
class RetrieverParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
    private val lesson = Lesson.parse(res("fixture_lesson.json"))
    private val expected = JSONObject(res("parity_expected.json"))
    private val retriever = Retriever(lesson.chunks)
    private fun ints(a: org.json.JSONArray) = (0 until a.length()).map { a.getInt(it) }

    @Test fun porterStemsMatchSqliteFts5() {
        val stems = expected.getJSONObject("stems")
        val bad = stems.keys().asSequence().filter { Porter.stem(it) != stems.getString(it) }.map { "$it: ${Porter.stem(it)} != ${stems.getString(it)}" }.toList()
        assertTrue("${bad.size} of ${stems.length()} stems differ: ${bad.take(15)}", bad.isEmpty())
    }

    @Test fun fiveKeyQuestionsMatchPython() {
        val qs = expected.getJSONArray("questions")
        assertEquals(5, qs.length())
        for (n in 0 until qs.length()) {
            val q = qs.getJSONObject(n)
            val id = q.getString("id")
            val s = retriever.score(q.getString("question"))
            val bm = q.getJSONObject("bm25")
            assertEquals("$id matched chunks", bm.length(), s.scores.size)
            for (k in bm.keys()) assertEquals("$id bm25 of chunk $k", bm.getDouble(k), s.scores.getValue(k.toInt()), 1e-9)
            assertEquals("$id bm25 rank", ints(q.getJSONArray("bm25_rank")), s.rank)
            assertEquals("$id guard", q.getBoolean("guard_ok"), retriever.guardOk(s.topBm25))
            val best = q.getJSONObject("best_sentence_score")
            for (k in best.keys()) assertEquals("$id best sentence of $k", best.getDouble(k), retriever.bestSentences(q.getString("question"), retriever.byId.getValue(k.toInt())).score, 1e-9)
            val rr = if (Text.words(q.getString("question")).size >= 2) retriever.rerank(q.getString("question"), s.rank, s.hybrid) else s.rank
            assertEquals("$id reranked", ints(q.getJSONArray("reranked")), rr)
            assertEquals("$id picked", ints(q.getJSONArray("picked")), retriever.pick(rr, q.getInt("budget")).takeIf { s.rank.isNotEmpty() && retriever.guardOk(s.topBm25) } ?: emptyList<Int>())
        }
    }
}
