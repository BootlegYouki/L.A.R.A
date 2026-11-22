package org.lara.experiment

import android.content.Context
import org.lara.experiment.llm.GenStats
import org.lara.experiment.llm.Llama
import org.lara.experiment.rag.Built
import org.lara.experiment.rag.Chunk
import org.lara.experiment.rag.Lesson
import org.lara.experiment.rag.Msg
import org.lara.experiment.rag.Redactor
import org.lara.experiment.rag.Retriever
import org.lara.experiment.rag.Tutor
import java.io.File

/** Everything about one tutor answer, for the chat and the Teacher view. */
class Turn(
    val built: Built,
    val shown: String,        // opener + model text after the output guard
    val raw: String,          // model text before the output guard
    val retrieveMs: Long,     // BM25 + rerank + prompt build (includes token counting)
    val ttftS: Double,        // start of generation (prompt evaluation included) to the first model token
    val stats: GenStats?,
    val redactedSentences: Int,
)

/** Model + lesson + prompt: the whole on-device tutor. */
class Engine(private val ctx: Context) {
    val dir: File = ctx.getExternalFilesDir(null)!!
    var lesson: Lesson? = null; private set
    private var retriever: Retriever? = null
    private val promptTemplate = ctx.assets.open("prompt_v7.txt").bufferedReader().readText()
    var nCtx = 2048; private set

    fun models(): List<File> = dir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
    fun lessonFile() = File(dir, "lesson.json")

    /** Loads the model, then reads lesson.json and counts each chunk's tokens with this model's tokenizer. */
    fun load(model: File, threads: Int, contextSize: Int): String? {
        if (!lessonFile().exists()) return ctx.getString(R.string.err_no_lesson, dir.absolutePath)
        if (!Llama.load(ctx.applicationInfo.nativeLibraryDir, model.absolutePath, contextSize, threads)) return ctx.getString(R.string.err_model_load, model.name)
        nCtx = contextSize
        val l = Lesson.parse(lessonFile().readText())
        l.chunks.forEach { it.tokens = Llama.count(it.piece()) }
        lesson = l
        retriever = Retriever(l.chunks)
        return null
    }

    fun answer(message: String, history: List<Msg>, onShown: (String) -> Unit = {}): Turn {
        val r = retriever ?: error("lesson not loaded")
        val t0 = System.nanoTime()
        val b = Tutor.build(message, history, r, promptTemplate, nCtx, Llama)
        val retrieveMs = (System.nanoTime() - t0) / 1_000_000
        b.forcedReply?.let { onShown(it); return Turn(b, it, it, retrieveMs, 0.0, null, 0) }
        val red = Redactor(b.sources.map { it.text }, b.sources.map { it.heading }, message, b.sources.map { it.page }.toSet())
        var shown = ""
        var pending = b.opener // the opener is prefilled into the model turn and shown together with the first model text
        fun emit(t: String) {
            if (t.isEmpty()) return
            shown += (if (pending.isNotEmpty()) pending + (if (t[0].isWhitespace()) "" else " ") else "") + t
            pending = ""
            onShown(shown)
        }
        var raw = ""
        var first = 0L
        val g0 = System.nanoTime()
        Llama.generate(b.promptText, Tutor.REPLY) { piece ->
            if (first == 0L) first = System.nanoTime()
            raw += piece
            emit(red.feed(piece))
            true
        }
        emit(red.feed("", final = true))
        if (red.out.isBlank() && b.sources.isNotEmpty()) { // everything was removed: same fallback as the desktop tool
            val g = b.grounded ?: b.sources.first()
            emit(if (pending.isNotEmpty()) "Read that part closely and tell me what you find." else "Let's look at the lesson together: reread [page ${g.page}] ${g.heading} and tell me what you find.")
        }
        val ttft = if (first == 0L) 0.0 else (first - g0) / 1e9
        return Turn(b, shown.ifEmpty { pending }, raw, retrieveMs, ttft, Llama.stats(), red.dropped)
    }

    fun sourcesOf(t: Turn): List<Chunk> = t.built.sources
}
