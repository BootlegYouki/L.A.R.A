package org.lara.app.ui.screens.homework

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.data.repository.HomeworkPhoto
import org.lara.app.data.repository.DocumentScanner
import org.lara.app.data.local.entities.AssignmentSubmissionEntity
import org.lara.app.domain.Cached

class HomeworkViewModel(private val container: AppContainer, private val assignmentId: String,
    private val saved: SavedStateHandle) : ViewModel() {
    val submission = container.homework.observe(assignmentId)
        .map<AssignmentSubmissionEntity?, Cached<AssignmentSubmissionEntity?>> { Cached.Content(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Cached.Loading)
    val draft = saved.getStateFlow<String?>("photo", null)
    val scanSource = saved.getStateFlow<String?>("scan_source", null)
    val scanPreview = saved.getStateFlow<String?>("scan_preview", null)
    val scanCorners = saved.getStateFlow("scan_corners", DocumentScanner.fullPage())
    val scanEnhance = saved.getStateFlow("scan_enhance", true)
    val scanDetected = saved.getStateFlow("scan_detected", false)
    val scanAvailable = saved.getStateFlow("scan_available", true)
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private val failed = MutableStateFlow(false)
    val error = failed.asStateFlow()

    fun captured(file: File) = prepare { file }

    fun choose(context: Context, uri: Uri) = prepare {
        val file = File.createTempFile("homework-import-", ".image", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64L * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
            }
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    private fun prepare(source: suspend () -> File) = action {
        val file = withContext(Dispatchers.IO) { source() }
        val photo = HomeworkPhoto.normalize(file, container.homeworkPhotos)
        val available = withContext(Dispatchers.IO) { DocumentScanner.available }
        val corners = if (available) {
            try { DocumentScanner.detect(photo) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        } else null
        val suggested = corners ?: DocumentScanner.fullPage()
        var previewFailed = false
        val preview = if (available) {
            try { DocumentScanner.render(photo, suggested, true, container.homeworkPhotos) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { previewFailed = true; null }
        } else null
        saved["scan_corners"] = suggested
        saved["scan_detected"] = corners != null
        saved["scan_available"] = available
        saved["scan_enhance"] = true
        saved["scan_preview"] = preview?.path
        saved["scan_source"] = photo.path
        failed.value = previewFailed
    }

    fun previewScan(corners: FloatArray, enhance: Boolean) = action {
        val source = ownedFile(requireNotNull(scanSource.value))
        val preview = DocumentScanner.render(source, corners.copyOf(), enhance, container.homeworkPhotos)
        deleteScanFile(scanPreview.value)
        saved["scan_corners"] = corners.copyOf()
        saved["scan_enhance"] = enhance
        saved["scan_preview"] = preview.path
    }

    fun adjustScan() = action {
        deleteScanFile(scanPreview.value)
        saved["scan_preview"] = null
    }

    fun cancelScan() = action { clearScan() }

    fun useScan(fullPhoto: Boolean) = action {
        val path = if (fullPhoto) scanSource.value else scanPreview.value
        val photo = HomeworkPhoto.compress(ownedFile(requireNotNull(path)), container.homeworkPhotos)
        val previous = draft.value
        saved["photo"] = photo.path
        clearScan()
        previous?.let { container.homework.discardDraft(it) }
    }

    private fun ownedFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(container.homeworkPhotos.canonicalPath + File.separator))
        return file
    }

    private suspend fun deleteScanFile(path: String?) = withContext(Dispatchers.IO) {
        path?.let {
            val file = ownedFile(it)
            require(file.name.startsWith("scan-") && file.extension == "png")
            file.delete()
        }
    }

    private suspend fun clearScan() {
        deleteScanFile(scanSource.value)
        deleteScanFile(scanPreview.value)
        saved["scan_source"] = null
        saved["scan_preview"] = null
    }

    private fun action(block: suspend () -> Unit) {
        if (working.value) return
        working.value = true
        failed.value = false
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed.value = true }
            finally { working.value = false }
        }
    }

    fun turnIn() {
        val path = draft.value ?: return
        action {
            container.homework.turnIn(assignmentId, ownedFile(path))
            saved["photo"] = null
        }
    }
}
