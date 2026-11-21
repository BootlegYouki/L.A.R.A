package org.lara.experiment.rag

import org.json.JSONObject

data class Chunk(val id: Int, val page: Int, val heading: String, val text: String, var tokens: Int = 0) {
    /** The block of lesson text the prompt shows for this chunk (same as `piece()` in the desktop rag.py). */
    fun piece() = "[page $page] $heading\n$text"
}

data class Lesson(val title: String, val chunks: List<Chunk>) {
    companion object {
        /** lesson.json: { title, chunks: [ { id, page, heading, text } ] }, written by the Hub (tools/export_lesson.py here). */
        fun parse(json: String): Lesson {
            val o = JSONObject(json)
            val a = o.getJSONArray("chunks")
            val chunks = (0 until a.length()).map {
                val c = a.getJSONObject(it)
                Chunk(c.getInt("id"), c.getInt("page"), c.optString("heading", ""), c.getString("text"), c.optInt("tokens", 0))
            }
            return Lesson(o.optString("title", "Lesson"), chunks)
        }
    }
}
