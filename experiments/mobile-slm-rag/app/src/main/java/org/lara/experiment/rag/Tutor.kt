package org.lara.experiment.rag

/** What the model server does for the desktop tool over HTTP: render the chat template and count tokens. */
interface ChatBackend {
    fun render(roles: List<String>, contents: List<String>): String
    fun count(text: String): Int
}

data class Msg(val role: String, val content: String)

class Built(
    val promptText: String,          // exact text sent to the model (template + opener prefill)
    val opener: String,              // written by the app, shown before the model text
    val forcedReply: String?,        // set for Filipino/Taglish: no model call
    val sources: List<Chunk>,
    val grounded: Chunk?,
    val guardBlocked: Boolean,
    val shortLesson: Boolean,
    val topBm25: Double,
    val promptTokens: Int,
    val budget: Int,
    val whyGuard: String,
    val rank: List<Int>,
    val messages: List<Msg>,
)

object Tutor {
    const val NO_MATCH = "(No part of the lesson matches this question.)"
    const val ENGLISH_ONLY = "I can only help in English. Please ask your question again in English."
    const val DECLINE = "This lesson does not cover that question. Please return to your lesson or ask your teacher."
    const val REPLY = 300
    private val FIL = "ano ang ng mga sagot ilan paano bakit saan sino kailan po yung nito natin namin hindi pwede naman lang kasi".split(" ").toSet()
    private val OPENERS_H = listOf("You'll find this on page {p}, in \"{h}\".", "Page {p} covers this, in the part called \"{h}\".", "Open your lesson to page {p}, to \"{h}\".",
        "This one is on page {p}, under \"{h}\".", "Check page {p}, in the section \"{h}\".", "The lesson explains this on page {p}, under \"{h}\".")
    private val OPENERS_P = listOf("You'll find this on page {p}.", "Page {p} covers this.", "This one is on page {p}.", "Check page {p} of the lesson.")

    // shortcut: function-word list, as in the desktop experiment; a Filipino message with none of these words slips through.
    fun isFilipino(t: String) = Regex("[a-z]+").findAll(t.lowercase()).any { it.value in FIL }

    fun systemPrompt(template: String, lesson: String) = template.replace("{reply_language}", "English").replace("{lesson_chunks}", lesson)

    fun trimHistory(h: List<Msg>) = h.filter { it.role == "user" || it.role == "assistant" }.takeLast(6).map { Msg(it.role, it.content.take(2000)) }

    fun build(message: String, history: List<Msg>, r: Retriever, promptTemplate: String, nCtx: Int, be: ChatBackend): Built {
        val hist = trimHistory(history)
        val prevUser = history.lastOrNull { it.role == "user" }?.content ?: ""
        val sc = r.score(message, prevUser)
        val base = be.count(systemPrompt(promptTemplate, ""))
        val histTok = hist.sumOf { be.count(it.content) }
        val budget = nCtx - base - histTok - REPLY
        var rank = sc.rank
        if (Text.words(message).size >= 2) rank = r.rerank(message, rank, sc.hybrid)
        val short = r.chunks.sumOf { it.tokens + 2 } <= budget
        val blocked = !short && !r.guardOk(sc.topBm25)
        val picked = (if (short) r.chunks.map { it.id } else if (blocked) emptyList() else r.pick(rank, budget)).toMutableList()
        fun render(): Triple<List<Chunk>, List<Msg>, String> {
            val order = picked.sortedBy { r.chunks.indexOfFirst { c -> c.id == it } }.map { r.byId.getValue(it) }
            val lesson = if (order.isEmpty()) NO_MATCH else order.joinToString("\n\n") { it.piece() }
            val msgs = listOf(Msg("system", systemPrompt(promptTemplate, lesson))) + hist + Msg("user", message)
            return Triple(order, msgs, be.render(msgs.map { it.role }, msgs.map { it.content }))
        }
        var (order, msgs, text) = render()
        var n = be.count(text)
        while (n + REPLY > nCtx && picked.isNotEmpty()) {
            picked.remove(picked.maxByOrNull { rank.indexOf(it) }) // the real template adds tokens: drop the lowest ranked chunk
            render().let { order = it.first; msgs = it.second; text = it.third }
            n = be.count(text)
        }
        val grounded = rank.firstOrNull { it in picked }?.let { r.byId[it] }
        val opener = if (order.isNotEmpty()) {
            val g = grounded ?: order.first()
            val tpl = (if (g.heading.isNotEmpty()) OPENERS_H else OPENERS_P).let { it[Math.floorMod(message.hashCode(), it.size)] }
            tpl.replace("{p}", g.page.toString()).replace("{h}", g.heading)
        } else ""
        val why = when {
            short -> "The whole lesson fits the budget, so every chunk was sent and the guard was not used."
            blocked -> "best bm25 %.2f < %.1f, so nothing was sent.".format(sc.topBm25, Retriever.BM25_MIN)
            else -> "best bm25 %.2f >= %.1f, so chunks were sent.".format(sc.topBm25, Retriever.BM25_MIN)
        }
        val forced = if (isFilipino(message)) ENGLISH_ONLY else null
        return Built(text + opener, opener, forced, order, grounded, blocked, short, sc.topBm25, n, budget, why, rank, msgs)
    }
}

/** Output guard from the desktop rag.py (V2..V7): drops a sentence that copies the lesson, repeats a lesson number the learner did not type, or cites a page that was not sent. */
class Redactor(bodies: List<String>, headings: List<String>, message: String, private val pages: Set<Int>) {
    private val num = Regex("\\d+(?:[.,]\\d+)*")
    private val w = { t: String -> Regex("[a-z0-9]+").findAll(t.lowercase()).map { it.value }.toList() }
    private val grams = bodies.flatMap { b -> w(b).let { x -> (0..maxOf(0, x.size - 10)).filter { it + 10 <= x.size }.map { x.subList(it, it + 10) } } }.toSet()
    private val bodyNums = bodies.flatMap { num.findAll(it).map { m -> m.value.trimEnd('.', ',') } }.toSet()
    private val okNums = (listOf(message) + headings).flatMap { num.findAll(it).map { m -> m.value.trimEnd('.', ',') } }.toSet()
    private val src: List<Set<String>>
    private var buf = ""
    var out = ""
    var dropped = 0

    init {
        val asked = cw(message)
        src = bodies.flatMap { b -> b.split(Regex("(?<=[.!?])\\s+|\\n+")).filter { it.split(Regex("\\s+")).size >= 6 && it.trim().last() in ".!?" } }
            .map { cw(it) - asked }.filter { it.size >= 4 }
    }

    private fun cw(t: String) = Text.words(t).map { Retriever.stem(it) }.toSet()

    private fun bad(sent: String): Boolean {
        val r = cw(sent)
        if (src.any { val i = r.intersect(it).size; i >= 4 && i >= 0.5 * it.size }) return true
        val cited = Regex("\\[page (\\d+)\\]|\\bpages?\\s+(\\d+(?:\\s*(?:,|and|to|-)\\s*\\d+)*)", RegexOption.IGNORE_CASE).findAll(sent)
            .flatMap { m -> m.groupValues.drop(1).flatMap { g -> Regex("\\d+").findAll(g).map { it.value.toInt() }.toList() } }.toSet()
        if ((cited - pages).isNotEmpty()) return true
        val x = w(sent)
        if (x.size >= 10 && (0..x.size - 10).any { x.subList(it, it + 10) in grams }) return true
        val nums = num.findAll(sent.replace(Regex("\\[page \\d+\\]|\\bpages? \\d+"), "")).map { it.value.trimEnd('.', ',') }.toSet()
        return (nums - okNums).intersect(bodyNums).isNotEmpty()
    }

    /** Feed generated text; returns the part that may be shown now. */
    fun feed(text: String, final: Boolean = false): String {
        buf += text
        val parts = buf.split(Regex("(?<=[.!?])\\s+|(?<=\\n)")).toMutableList()
        val keep = if (final) "" else parts.removeAt(parts.size - 1)
        var res = ""
        for (p in parts) {
            if (p.isBlank()) { res += p; continue }
            if (bad(p)) dropped++ else res += p + (if (p.endsWith("\n")) "" else " ")
        }
        buf = keep
        out += res
        return res
    }
}
