package org.lara.app.ui.screens.homework

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.domain.model.Assignment
import org.lara.app.domain.Cached
import org.lara.app.ui.components.ClassroomSurface
import org.lara.app.ui.theme.*

@Composable
fun HomeworkScreen(container: AppContainer, userId: String, assignment: Assignment, close: () -> Unit) {
    val model: HomeworkViewModel = viewModel(key = "homework-$userId-${assignment.id}", factory = viewModelFactory {
        initializer { HomeworkViewModel(container, assignment.id, createSavedStateHandle()) }
    })
    val submissionState by model.submission.collectAsStateWithLifecycle()
    val submission = (submissionState as? Cached.Content)?.value
    val draft by model.draft.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val scanSource by model.scanSource.collectAsStateWithLifecycle()
    val scanPreview by model.scanPreview.collectAsStateWithLifecycle()
    val scanCorners by model.scanCorners.collectAsStateWithLifecycle()
    val scanEnhance by model.scanEnhance.collectAsStateWithLifecycle()
    val scanDetected by model.scanDetected.collectAsStateWithLifecycle()
    val scanAvailable by model.scanAvailable.collectAsStateWithLifecycle()
    var camera by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { model.choose(context.applicationContext, it) }
    }
    BackHandler {
        if (camera) camera = false
        else if (scanSource != null) model.cancelScan()
        else close()
    }
    if (submissionState == Cached.Loading) {
        org.lara.app.ui.screens.stream.ReadingSkeleton()
        return
    }
    if (camera) {
        CameraCaptureScreen({ file -> camera = false; model.captured(file) }, { camera = false })
        return
    }
    val source = scanSource
    if (source != null) {
        DocumentReviewScreen(source, scanPreview, container.homeworkPhotos, scanCorners, scanEnhance,
            scanDetected, scanAvailable, busy, error, model::previewScan, model::adjustScan,
            { model.useScan(false) }, { model.useScan(true) }, model::cancelScan)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        OutlinedButton(onClick = close, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.settings_back)) }
        Text(assignment.title, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.homework_due, DateFormat.getDateTimeInstance().format(Date(assignment.dueDate))))
        Text(assignment.instructions)
        ClassroomSurface {
            Text(stringResource(R.string.homework_your_work), style = MaterialTheme.typography.titleLarge)
            val status = if (draft != null) R.string.homework_photo_ready else when (submission?.syncStatus) {
                "QUEUED_FOR_SYNC" -> R.string.homework_queued
                "REJECTED" -> R.string.homework_rejected
                "SYNCED" -> R.string.homework_turned_in
                else -> R.string.homework_to_do
            }
            Text(stringResource(status), style = MaterialTheme.typography.labelLarge)
            val path = draft ?: submission?.filePath
            PhotoPreview(path, container.homeworkPhotos)
            if (draft == null && submission?.syncStatus == "QUEUED_FOR_SYNC") Text(stringResource(R.string.homework_offline_help))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.homework_preparing))
            }
            if (error) Text(stringResource(R.string.homework_failed))
            if (submission?.score == null) {
                Button(onClick = { camera = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(if (path.isNullOrEmpty()) R.string.homework_take_photo else R.string.homework_retake))
                }
                OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.homework_choose_photo)) }
                Button(onClick = model::turnIn, enabled = draft != null && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.homework_turn_in)) }
            }
        }
    }
}

@Composable
private fun PhotoPreview(path: String?, root: File) {
    val current = rememberHomeworkBitmap(path, root)
    if (current == null) Text(stringResource(R.string.homework_photo_hint))
    else Image(current.asImageBitmap(), stringResource(R.string.homework_photo_description), Modifier.fillMaxWidth().heightIn(max = 280.dp))
}

@Composable
internal fun rememberHomeworkBitmap(path: String?, root: File): Bitmap? {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                if (!path.isNullOrEmpty()) {
                    val file = File(path).canonicalFile
                    if (file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) {
                        decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                }
            }
            bitmap = decoded
            awaitCancellation()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { bitmap = null }
        finally {
            bitmap = null
            decoded?.recycle()
        }
    }
    return bitmap
}
