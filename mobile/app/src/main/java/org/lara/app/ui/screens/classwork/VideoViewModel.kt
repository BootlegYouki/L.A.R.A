package org.lara.app.ui.screens.classwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.lara.app.domain.*

data class VideoState(val loading: Boolean = true, val source: VideoSource? = null, val problem: VideoProblem? = null)

class VideoViewModel(private val repository: VideoRepository, private val id: String) : ViewModel() {
    val material = repository.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
    private val mutableState = MutableStateFlow(VideoState())
    val state = mutableState.asStateFlow()
    private val mutableDownload = MutableStateFlow(VideoDownload())
    val download = mutableDownload.asStateFlow()
    private var loading: Job? = null
    private var transfer: Job? = null
    private var resumeOnStart = false
    fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            mutableState.value = VideoState()
            try {
                mutableDownload.value = repository.progress(id)
                mutableState.value = VideoState(false, repository.source(id))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = VideoState(false, problem = problem(e)) }
        }
    }
    fun save() {
        if (transfer?.isActive == true) return
        resumeOnStart = true
        transfer = viewModelScope.launch {
            while (true) {
                try {
                    mutableDownload.update { it.copy(phase = DownloadPhase.DOWNLOADING, problem = null) }
                    repository.save(id) { mutableDownload.value = it }
                    mutableState.value = VideoState(false, repository.source(id))
                    resumeOnStart = false
                    break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
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
