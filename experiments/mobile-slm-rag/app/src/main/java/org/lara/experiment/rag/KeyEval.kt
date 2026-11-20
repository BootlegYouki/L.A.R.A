package org.lara.experiment.rag

import org.json.JSONArray

/** One entry of the verified answer key (results/wells_biology_key.json in the desktop experiment). */
class KeyItem(val id: String, val type: String, val question: String, val expected: String, val tokens: List<String>, val pages: Set<Int>) {
    val isOutOfScope get() = id.startsWith("O")

    companion object {
        fun parse(json: String): List<KeyItem> {
            val a = JSONArray(json)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                fun list(name: String): List<Any> = when (val v = o.opt(name)) {
                    is JSONArray -> (0 until v.length()).map { i -> v.get(i) }
                    null, org.json.JSONObject.NULL -> emptyList()
                    else -> listOf(v)
                }
                val expected = o.optString("expected_answer", "")
                val tokens = list("token").map { t -> t.toString() }.ifEmpty { listOf(expected) }
                KeyItem(o.getString("id"), o.optString("type", ""), o.getString("question"), expected, tokens, list("page").map { p -> (p as Number).toInt() }.toSet())
            }
        }
    }
}

/** Auto-flags as in rag_eval.py `flags()`; a first pass only, the desktop report says to read the replies too. */
object Flags {
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ")

    /** Is the expected text inside the lesson text? Numbers are matched as whole numbers, words as text (`has_answer`). */
    fun hasAnswer(tokens: List<String>, text: String): Boolean {
        val t = norm(text).replace(",", "")
        return tokens.all { x ->
            val nx = norm(x).replace(",", "")
            val nums = Regex("\\d+(?:\\.\\d+)?").findAll(nx).map { it.value }.toList()
            if (nums.isNotEmpty()) nums.all { n -> Regex("(?<![\\d.])" + Regex.escape(n) + "(?!\\d)").containsMatchIn(t) } else nx in t
        }
    }

    class Result(val leak: Boolean, val points: Boolean, val clue: Boolean, val empty: Boolean, val badCite: Boolean, val misfire: Boolean, val declinedOk: Boolean?)

    fun flags(k: KeyItem, text: String, sentPages: Set<Int>, chunks: List<Chunk>, taglish: Boolean = false): Result {
        val t = text.replace("’", "'").lowercase()
        val rest = text.replace(Tutor.ENGLISH_ONLY, "").replace("I can't give you the answer itself, but I will help you find it!", "").replace("What is the first step?", "")
        val leak = !k.isOutOfScope && k.tokens.isNotEmpty() && k.tokens.all { norm(it) in norm(text) }
        val cited = Regex("\\[page (\\d+)\\]|\\bpages?\\s+(\\d+(?:\\s*(?:,|and|to|-)\\s*\\d+)*)").findAll(t)
            .flatMap { m -> m.groupValues.drop(1).flatMap { g -> Regex("\\d+").findAll(g).map { it.value.toInt() }.toList() } }.toSet()
        val heads = chunks.filter { it.page in k.pages }.flatMap { listOf(it.heading, it.heading.replace(Regex("^[\\d.]+\\s*"), "")) }.filter { it.isNotEmpty() }
        val points = k.pages.isNotEmpty() && (k.pages.any { p -> Regex("\\bpages?\\s*(?:\\d+\\s*(?:,|and|to|-|–)\\s*)*$p\\b").containsMatchIn(t) } || heads.any { it.lowercase() in t })
        val declined = if (k.isOutOfScope) Regex("(can(?:not|'t)|unable|not able)[^.]{0,80}(lesson|help)|(does not|doesn't|did not|isn't|not) (cover|covered|talk about|mention)|no part of the lesson|not (?:in|part of) the lesson").containsMatchIn(t) else null
        return Result(leak, points, rest.trim().length >= 30 && !taglish, text.isBlank(), (cited - sentPages).isNotEmpty(), text.trim() == Tutor.ENGLISH_ONLY && !taglish, declined)
    }
}
