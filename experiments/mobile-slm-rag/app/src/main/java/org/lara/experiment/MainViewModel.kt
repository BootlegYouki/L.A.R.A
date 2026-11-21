package org.lara.experiment

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lara.experiment.llm.Llama
import org.lara.experiment.rag.Msg
import java.io.File

class ChatLine(val fromUser: Boolean, text: String) { var text by mutableStateOf(text) }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = Engine(app)
    private val runner = RunKey(app, engine)

    var models by mutableStateOf(engine.models()); private set
    var selected by mutableStateOf<File?>(models.firstOrNull()); private set
    var threads by mutableStateOf(4)
    var ctxSize by mutableStateOf(2048)
    var status by mutableStateOf(app.getString(R.string.not_loaded)); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var lastTurn by mutableStateOf<Turn?>(null); private set
    var teacherOpen by mutableStateOf(false)
    var runStatus by mutableStateOf(""); private set
    val chat = mutableStateListOf<ChatLine>()
    val dir get() = engine.dir
    private var loadSeconds = 0.0
    @Volatile private var stopRun = false
    private val history = ArrayList<Msg>()

    init { runner.recordKilledRun()?.let { runStatus = app.getString(R.string.killed_before, it) } }

    fun refresh() { models = engine.models(); if (selected !in models) selected = models.firstOrNull() }
    fun select(f: File) { selected = f }

    fun load() {
        val m = selected ?: return
        viewModelScope.launch {
            ready = false; busy = true
            status = getApplication<Application>().getString(R.string.loading, m.name)
            val t0 = System.nanoTime()
            val err = withContext(Dispatchers.Default) { engine.load(m, threads, ctxSize) }
            loadSeconds = (System.nanoTime() - t0) / 1e9
            ready = err == null
            status = err ?: getApplication<Application>().getString(R.string.loaded, m.name, engine.lesson!!.chunks.size, loadSeconds)
            busy = false
        }
    }

    fun send(text: String) {
        if (text.isBlank() || !ready || busy) return
        val reply = ChatLine(false, "…")
        chat += ChatLine(true, text); chat += reply
        busy = true
        viewModelScope.launch {
            val t = withContext(Dispatchers.Default) { engine.answer(text, history.toList()) { reply.text = it } }
            reply.text = t.shown
            history += Msg("user", text); history += Msg("assistant", t.shown)
            lastTurn = t
            busy = false
        }
    }

    fun stop() { Llama.cancel(); stopRun = true }

    fun runKey() {
        val m = selected ?: return
        if (!ready || busy) return
        busy = true; stopRun = false
        viewModelScope.launch {
            val id = withContext(Dispatchers.Default) {
                runner.run(m, threads, loadSeconds, { d, n, q -> runStatus = getApplication<Application>().getString(R.string.run_progress, d, n, q) }, { stopRun })
            }
            runStatus = getApplication<Application>().getString(R.string.run_done, id)
            busy = false
        }
    }
}
