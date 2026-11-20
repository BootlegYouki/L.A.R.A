package org.lara.experiment.rag

import java.text.Normalizer
import kotlin.math.ln

/**
 * BM25 + sentence rerank + "not covered" guard, ported from the desktop experiment's rag.py.
 * No embedding model on the phone, so the "hybrid" score of the desktop tool is the BM25 rank alone (reciprocal-rank, k=60).
 */
object Text {
    val STOP = "the and for are was what how why who when where which this that with from you your can does did not ano ang mga para yung kung bakit paano saan sino ito iyan iyon siya sila ako ikaw natin namin nang lang din rin pa po".split(" ").toSet()
    private val WORD = Regex("[a-z0-9]+")

    /** Query words: lowercase [a-z0-9]+, at least 3 chars, no stop words (`words()` in rag.py). */
    fun words(t: String): List<String> = WORD.findAll(t.lowercase()).map { it.value }.filter { it.length >= 3 && it !in STOP }.toList()

    /** Index tokens as SQLite FTS5 `unicode61` makes them: letters/digits, lowercased, diacritics removed. */
    fun fts5Tokens(t: String): List<String> {
        val n = Normalizer.normalize(t, Normalizer.Form.NFD)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in n) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch) || type == Character.PRIVATE_USE.toInt()) sb.append(ch.lowercaseChar())
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

/** Same scoring as SQLite FTS5 `bm25()` over (heading, text) with the porter tokenizer: k1=1.2, b=0.75. */
class Bm25(private val chunks: List<Chunk>) {
    private val tf = chunks.map { c -> HashMap<String, Int>().also { m -> Text.fts5Tokens(c.heading).plus(Text.fts5Tokens(c.text)).forEach { w -> val s = Porter.stem(w); m[s] = (m[s] ?: 0) + 1 } } }
    private val len = chunks.map { Text.fts5Tokens(it.heading).size + Text.fts5Tokens(it.text).size }
    private val avg = len.sum().toDouble() / chunks.size.coerceAtLeast(1)
    private val df = HashMap<String, Int>().also { d -> tf.forEach { m -> m.keys.forEach { k -> d[k] = (d[k] ?: 0) + 1 } } }

    /** Score per chunk id (higher is better; the Python side negates FTS5's value). Only chunks matching a query word appear. */
    fun scores(queryWords: Set<String>): Map<Int, Double> {
        val phrases = queryWords.sorted().map { Porter.stem(it) } // two words with one stem count twice, as two FTS5 phrases do
        val n = chunks.size
        val out = LinkedHashMap<Int, Double>()
        for ((i, c) in chunks.withIndex()) {
            var s = 0.0
            var hit = false
            for (p in phrases) {
                val f = tf[i][p] ?: continue
                hit = true
                val d = df.getValue(p)
                var idf = ln((n - d + 0.5) / (d + 0.5))
                if (idf <= 0.0) idf = 1e-6
                s += idf * (f * 2.2) / (f + 1.2 * (1 - 0.75 + 0.75 * len[i] / avg))
            }
            if (hit) out[c.id] = s
        }
        return out
    }
}

class Retriever(val chunks: List<Chunk>) {
    val byId = chunks.associateBy { it.id }
    private val bm = Bm25(chunks)
    private val idfTable: Map<String, Double>
    private val unseen: Double

    init {
        val df = HashMap<String, Int>()
        for (c in chunks) Text.words(c.heading + " " + c.text).map(::stem).toSet().forEach { df[it] = (df[it] ?: 0) + 1 }
        idfTable = df.mapValues { ln((chunks.size + 1.0) / (it.value + 0.5)) + 1 }
        unseen = ln(chunks.size + 1.0) + 1
    }

    class Scored(val scores: Map<Int, Double>, val rank: List<Int>, val topBm25: Double, val hybrid: Map<Int, Double>)

    fun score(message: String, prevUser: String = ""): Scored {
        val q = Text.words("$message $prevUser").toSet()
        val s = if (q.isEmpty()) emptyMap() else bm.scores(q)
        val rank = s.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
        val hy = rank.withIndex().associate { (r, id) -> id to 1.0 / (60 + r + 1) }
        return Scored(s, rank, s.values.maxOrNull() ?: 0.0, hy)
    }

    /** "Not covered" guard: send chunks only when the lesson shares real words with the question (top BM25 >= 5.0, calibration.json). */
    fun guardOk(top: Double) = top >= BM25_MIN

    class Sent(val score: Double, val idx: Int, val sents: List<String>, val all: List<Double>)

    fun bestSentences(message: String, c: Chunk): Sent {
        val q = Text.words(message).map(::stem).toSet()
        val wt = { w: String -> (idfTable[w] ?: unseen) * (if (w.any { it.isDigit() }) 1.5 else 1.0) }
        val total = q.sumOf(wt).takeIf { it != 0.0 } ?: 1.0
        val sents = sentencesOf(c.text).ifEmpty { listOf(c.text) }
        val sc = sents.map { s -> q.intersect(Text.words(s).map(::stem).toSet()).sumOf(wt) / total }
        val k = sc.indices.maxByOrNull { sc[it] }!! // first maximum, as Python's max()
        return Sent(sc[k], k, sents, sc)
    }

    /** Reorder the top 8 by (score / best score) + 0.2 x best-sentence score; ties keep the old order. */
    fun rerank(message: String, rank: List<Int>, hybrid: Map<Int, Double>, top: Int = 8): List<Int> {
        val head = rank.take(top)
        val rows = head.associateWith { bestSentences(message, byId.getValue(it)).score }
        val mx = head.maxOfOrNull { hybrid[it] ?: 0.0 }?.takeIf { it != 0.0 } ?: 1.0
        val sorted = head.sortedWith(compareBy({ -((hybrid[it] ?: 0.0) / mx + BLEND * rows.getValue(it)) }, { rank.indexOf(it) }))
        return sorted + rank.drop(top)
    }

    /** Highest-ranked chunks while they fit the lesson-token budget, at most MAX_CHUNKS. */
    fun pick(rank: List<Int>, budget: Int): List<Int> {
        val out = ArrayList<Int>()
        var used = 0
        for (i in rank) {
            val t = byId.getValue(i).tokens
            if (used + t + 2 > budget || out.size >= MAX_CHUNKS) break
            out.add(i); used += t + 2
        }
        return out
    }

    companion object {
        const val MAX_CHUNKS = 3
        const val BM25_MIN = 5.0
        const val BLEND = 0.2
        fun stem(w: String) = if (w.length > 4) w.replace(Regex("(ing|ed|es|s|e)$"), "") else w
        fun sentencesOf(t: String) = t.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.split(Regex("\\s+")).size >= 3 }
    }
}
