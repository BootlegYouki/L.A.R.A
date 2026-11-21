package org.lara.experiment.llm

import org.lara.experiment.rag.ChatBackend

fun interface TokenSink {
    /** Return false to stop generating. */
    fun onToken(piece: String): Boolean
}

/** Generation timings from the native side. */
class GenStats(val promptTokens: Int, val promptMs: Double, val genTokens: Int, val genMs: Double) {
    val tokPerSec get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

/** llama.cpp b11938 through JNI, CPU only. One model at a time. */
object Llama : ChatBackend {
    init { System.loadLibrary("lara-llm") }

    private external fun nInit(nativeLibDir: String)
    private external fun nLoad(path: String, nCtx: Int, nThreads: Int): Int
    private external fun nUnload()
    private external fun nCancel()
    private external fun nTokenCount(text: String): Int
    private external fun nRenderChat(roles: Array<String>, contents: Array<String>): String
    private external fun nGenerate(prompt: String, maxTokens: Int, sink: TokenSink): Int
    private external fun nStats(): DoubleArray
    external fun nSystemInfo(): String

    private var inited = false
    @Volatile var loaded = false
        private set

    @Synchronized
    fun load(nativeLibDir: String, path: String, nCtx: Int, threads: Int): Boolean {
        if (!inited) { nInit(nativeLibDir); inited = true }
        unload()
        loaded = nLoad(path, nCtx, threads) == 0
        return loaded
    }

    @Synchronized
    fun unload() { if (loaded) nUnload(); loaded = false }

    fun cancel() = nCancel()

    override fun count(text: String) = nTokenCount(text)
    override fun render(roles: List<String>, contents: List<String>) = nRenderChat(roles.toTypedArray(), contents.toTypedArray())

    /** Returns 0 on success; the prompt text is fed as is (it already holds the chat template and any prefilled opener). */
    @Synchronized
    fun generate(prompt: String, maxTokens: Int, sink: TokenSink): Int = nGenerate(prompt, maxTokens, sink)

    fun stats(): GenStats = nStats().let { GenStats(it[0].toInt(), it[1], it[2].toInt(), it[3]) }
}
