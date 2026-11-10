package org.lara.app.ui.screens.homework

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
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
import org.lara.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
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
    var workOpen by rememberSaveable(assignment.id) { mutableStateOf(false) }
    var adding by rememberSaveable(assignment.id) { mutableStateOf(false) }
    var confirming by rememberSaveable(assignment.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { model.choose(context.applicationContext, it) }
    }
    BackHandler {
        if (camera) camera = false
        else if (scanSource != null) model.cancelScan()
        else if (confirming) confirming = false
        else if (adding) adding = false
        else if (workOpen) workOpen = false
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
    val path = draft ?: submission?.filePath
    val status = if (draft != null) R.string.homework_photo_ready else when (submission?.syncStatus) {
        "QUEUED_FOR_SYNC" -> R.string.homework_queued
        "REJECTED" -> R.string.homework_rejected
        "SYNCED" -> R.string.homework_turned_in
        else -> R.string.homework_to_do
    }
    val statusIcon = when {
        draft != null -> R.drawable.ic_info
        submission?.syncStatus == "SYNCED" -> R.drawable.ic_check_circle
        submission != null -> R.drawable.ic_warning_circle
        else -> R.drawable.ic_info
    }
    @Composable fun WorkContent(expanded: Boolean) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            TextButton(onClick = { workOpen = !workOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.homework_your_work), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f), color = LaraColors.TextPrimary)
                Icon(painterResource(if (expanded) R.drawable.ic_arrow_left else R.drawable.ic_corners_out),
                    stringResource(if (expanded) R.string.homework_close_work else R.string.homework_open_work), Modifier.size(24.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.Top) {
                Icon(painterResource(statusIcon), null, Modifier.size(24.dp), tint = LaraColors.TextSecondary)
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            }
            if (expanded) {
                PhotoPreview(path, container.homeworkPhotos)
                if (submission?.syncStatus == "REJECTED") {
                    when (submission?.syncRejectReason) {
                        "LATE_SUBMISSION" -> Text(stringResource(R.string.homework_rejected_late))
                        "ALREADY_GRADED" -> Text(stringResource(R.string.homework_rejected_graded))
                    }
                }
                if (draft == null && submission?.syncStatus == "QUEUED_FOR_SYNC") Text(stringResource(R.string.homework_offline_help))
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.homework_preparing))
            }
            if (error) Text(stringResource(R.string.homework_failed), color = LaraColors.DangerDark)
            if (submission?.score == null) {
                OutlinedButton(onClick = { adding = true }, enabled = !busy, shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.homework_add_work))
                }
                if (expanded || draft != null) Button(onClick = { confirming = true }, enabled = draft != null && !busy,
                    shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.homework_turn_in))
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
            IconButton(onClick = close, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back))
            }
            Text(stringResource(R.string.homework_due, DateFormat.getDateTimeInstance().format(Date(assignment.dueDate))),
                color = LaraColors.TextSecondary)
            Text(assignment.title, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.homework_points, assignment.maxPoints), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = LaraColors.Border)
            Text(assignment.instructions, style = MaterialTheme.typography.bodyLarge)
        }
        Surface(modifier = Modifier.weight(1f, fill = false), color = LaraColors.SurfaceSubtle, shape = LaraShapes.extraLarge) { WorkContent(false) }
    }
    if (workOpen) ModalBottomSheet(onDismissRequest = { workOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LaraColors.SurfaceSubtle, shape = LaraShapes.extraLarge) {
        WorkContent(true)
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LaraColors.Surface, shape = LaraShapes.extraLarge) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Text(stringResource(R.string.homework_add_work), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { adding = false; workOpen = false; camera = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Icon(painterResource(R.drawable.ic_corners_out), null, Modifier.size(24.dp))
                Spacer(Modifier.width(LaraSpacing.S3))
                Text(stringResource(if (path.isNullOrEmpty()) R.string.homework_take_photo else R.string.homework_retake))
            }
            TextButton(onClick = { adding = false; gallery.launch("image/*") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(24.dp))
                Spacer(Modifier.width(LaraSpacing.S3))
                Text(stringResource(R.string.homework_choose_photo))
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, shape = LaraShapes.extraLarge,
        containerColor = LaraColors.Surface,
        title = { Text(stringResource(R.string.homework_confirm_title)) },
        text = { Text(stringResource(R.string.homework_confirm_photo), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(enabled = draft != null && !busy, onClick = { confirming = false; model.turnIn() },
            modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.homework_turn_in)) } },
        dismissButton = { TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.enrollment_cancel_action))
        } })
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
