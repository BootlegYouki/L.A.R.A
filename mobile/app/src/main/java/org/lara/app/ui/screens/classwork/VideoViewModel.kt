package org.lara.app.ui.screens.classwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.lara.app.domain.*

data class VideoState(val loading: Boolean = true, val source: VideoSource? = null, val problem: VideoProblem? = null,
    val removing: Boolean = false)

class VideoViewModel(private val repository: VideoRepository, private val id: String) : ViewModel() {
    val material = repository.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
    private val mutableState = MutableStateFlow(VideoState())
    val state = mutableState.asStateFlow()
    private val mutableDownload = MutableStateFlow(VideoDownload())
    val download = mutableDownload.asStateFlow()
    private var loading: Job? = null
    private var transfer: Job? = null
    private var resumeOnStart = false
    private var removal: Job? = null
    fun load() {
        if (removal?.isActive == true) return
        loading?.cancel()
        loading = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, problem = null) }
            try {
                mutableDownload.value = repository.progress(id)
                mutableState.value = VideoState(false, repository.source(id))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = VideoState(false, problem = problem(e)) }
        }
    }
    fun save() {
        if (removal?.isActive == true) return
        if (transfer?.isActive == true) return
        resumeOnStart = true
        transfer = viewModelScope.launch {
            val context = currentCoroutineContext()
            while (true) {
                try {
                    mutableDownload.update { it.copy(phase = DownloadPhase.DOWNLOADING, problem = null) }
                    repository.save(id) { progress ->
                        mutableDownload.update { previous -> if (context.isActive) progress else previous }
                    }
                    context.ensureActive()
                    val source = repository.source(id)
                    context.ensureActive()
                    mutableState.value = VideoState(false, source)
                    resumeOnStart = false
                    break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Closing a cancelled transport can throw something other than CancellationException.
                    context.ensureActive()
                    val problem = problem(e)
                    mutableDownload.update { it.copy(phase = if (problem == VideoProblem.HUB_UNAVAILABLE)
                        DownloadPhase.PAUSED else DownloadPhase.FAILED, problem = problem) }
                    if (problem != VideoProblem.HUB_UNAVAILABLE) { resumeOnStart = false; break }
                    delay(3_000)
                }
            }
        }
    }
    fun pause() { resumeOnStart = false; stop() }
    fun remove() {
        if (removal?.isActive == true) return
        resumeOnStart = false
        removal = viewModelScope.launch {
            loading?.cancelAndJoin()
            transfer?.cancelAndJoin(); transfer = null
            mutableState.value = VideoState(loading = false, removing = true)
            try {
                repository.remove(id)
                mutableDownload.value = VideoDownload()
                mutableState.value = try { VideoState(false, repository.source(id)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { VideoState(false, problem = problem(e)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val source = runCatching { repository.source(id) }.getOrNull()
                mutableDownload.value = runCatching { repository.progress(id) }.getOrDefault(VideoDownload())
                mutableState.value = VideoState(false, source, problem(e))
            }
        }
    }
    fun stop() {
        loading?.cancel()
        transfer?.cancel(); transfer = null
        if (mutableDownload.value.phase == DownloadPhase.DOWNLOADING)
            mutableDownload.update { it.copy(phase = DownloadPhase.PAUSED) }
    }
    fun start() { load(); if (resumeOnStart) save() }
    private fun problem(error: Exception): VideoProblem = when (error) {
        is VideoFailure -> error.problem
        is IOException -> VideoProblem.HUB_UNAVAILABLE
        else -> VideoProblem.UNAVAILABLE
    }
}
