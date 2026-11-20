package org.lara.app.ui.screens.homework

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
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
import org.lara.app.data.repository.HomeworkAttachment
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
    var adding by rememberSaveable(assignment.id) { mutableStateOf(false) }
    var confirming by rememberSaveable(assignment.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { model.choose(context.applicationContext, it) }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.chooseDocument(context.applicationContext, it) }
    }
    BackHandler {
        if (camera) camera = false
        else if (scanSource != null) model.cancelScan()
        else if (confirming) confirming = false
        else if (adding) adding = false
        else close()
    }
    if (submissionState == Cached.Loading) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
            Row(Modifier.fillMaxWidth().background(LaraColors.PrimaryDark).statusBarsPadding().height(AppBarHeight).padding(horizontal = LaraSpacing.S3), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = close, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back), Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Box(Modifier.weight(1f)) { org.lara.app.ui.components.LoadingSpinner() }
        }
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
    val status = if (draft != null) R.string.homework_work_ready else when (submission?.syncStatus) {
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
    val comments by model.comments.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var up by rememberSaveable(assignment.id) { mutableStateOf(false) }
    var note by rememberSaveable(assignment.id) { mutableStateOf("") }
    val due = DateFormat.getDateTimeInstance().format(Date(assignment.dueDate))
    // The sheet is our own so it can shut while the finger is still down, like Classroom's (BottomSheetScaffold can't).
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
        val statusBar = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // The sheet stops under the status bar when swiped up, leaving it visible like Classroom does.
        val collapsedPx = with(LocalDensity.current) { (maxHeight - statusBar - (if (draft != null) 248.dp else 176.dp) - androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).toPx() }
        val offset = remember(collapsedPx) { Animatable(if (up) 0f else collapsedPx) }
        // Content appears only once the sheet has settled at the top, so it stays blank while sliding.
        val full = up
        BackHandler(up) { up = false; scope.launch { offset.animateTo(collapsedPx) } }
        val drag = Modifier.pointerInput(collapsedPx) {
            var closed = false
            detectVerticalDragGestures(
                onDragStart = { closed = false },
                onDragEnd = { if (!closed) { up = offset.value < collapsedPx * 0.5f; scope.launch { offset.animateTo(if (up) 0f else collapsedPx) } } },
                onDragCancel = { if (!closed) { up = offset.value < collapsedPx * 0.5f; scope.launch { offset.animateTo(if (up) 0f else collapsedPx) } } },
            ) { change, dy ->
                change.consume()
                if (closed) return@detectVerticalDragGestures
                val next = (offset.value + dy).coerceIn(0f, collapsedPx)
                if (dy > 0 && next > collapsedPx * 0.7f) { closed = true; up = false; scope.launch { offset.snapTo(collapsedPx) } }
                else {
                    if (next <= 0f) { up = true; closed = true } // reached the top: let go of the drag // content shows while the finger is still down
                    scope.launch { offset.snapTo(next) }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(LaraColors.PrimaryDark).statusBarsPadding().height(AppBarHeight).padding(horizontal = LaraSpacing.S3), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = close, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back), Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
        org.lara.app.ui.components.PullRefresh({ container.sync.synchronize(userId) }, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Text(assignment.title, style = MaterialTheme.typography.headlineMedium)
                Text(pluralStringResource(R.plurals.homework_points, assignment.maxPoints.toInt(), assignment.maxPoints.toInt()),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.homework_due, due), style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
                HorizontalDivider(color = LaraColors.Border)
                Text(assignment.instructions, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        }
        Surface(Modifier.padding(top = statusBar).offset { IntOffset(0, offset.value.roundToInt()) }.fillMaxWidth().height(maxHeight - statusBar), color = LaraColors.SurfaceSubtle,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 32.dp).then(drag), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = LaraSpacing.S4)
                    .padding(bottom = LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                    Row(Modifier.fillMaxWidth().then(drag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Text(stringResource(R.string.homework_your_work), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = LaraColors.TextPrimary)
                        if (comments.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_chats_regular), null, Modifier.size(20.dp))
                            Text(comments.size.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(due, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextPrimary)
                    }
                    // Classroom's collapsed sheet shows only the title row and the buttons; the rest appears when it is swiped up.
                    // Bottom padding keeps the scrolling content clear of the pinned buttons.
                    if (full) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 260.dp), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.Top) {
                            Icon(painterResource(statusIcon), null, Modifier.size(24.dp), tint = LaraColors.TextSecondary)
                            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.homework_saving))
                        }
                        if (error) Text(stringResource(R.string.homework_save_failed), color = LaraColors.DangerDark)
                        Text(stringResource(R.string.homework_attachments), style = MaterialTheme.typography.titleMedium)
                        HomeworkAttachmentPreview(path, container.homeworkPhotos)
                        if (submission?.syncStatus == "REJECTED") {
                            when (submission?.syncRejectReason) {
                                "LATE_SUBMISSION" -> Text(stringResource(R.string.homework_rejected_late))
                                "ALREADY_GRADED" -> Text(stringResource(R.string.homework_rejected_graded))
                            }
                        }
                        if (draft == null && submission?.syncStatus == "QUEUED_FOR_SYNC") Text(stringResource(R.string.homework_offline_help))
                        Text(stringResource(R.string.homework_private_comments), style = MaterialTheme.typography.titleMedium)
                        if (comments.isEmpty()) Text(stringResource(R.string.homework_no_comments), style = MaterialTheme.typography.bodyLarge)
                        comments.forEach { c ->
                            Column {
                                Text(stringResource(if (c.authorId == userId) R.string.homework_you else R.string.homework_teacher) + " \u2022 " +
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(c.createdAt)),
                                    style = MaterialTheme.typography.bodySmall, color = LaraColors.TextSecondary)
                                Text(c.content, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
        
                    }
                }
            }
        }
        // Solid like the sheet, so content sliding underneath is hidden behind the buttons as in Classroom.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(androidx.compose.foundation.layout.WindowInsets.navigationBars).background(LaraColors.SurfaceSubtle))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(LaraColors.SurfaceSubtle).navigationBarsPadding().padding(horizontal = LaraSpacing.S4)
            .padding(top = LaraSpacing.S3, bottom = LaraSpacing.S5), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            if (full) {
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), shape = CircleShape,
                        placeholder = { Text(stringResource(R.string.homework_comment_hint)) }, singleLine = true,
                        trailingIcon = {
                            IconButton(enabled = note.isNotBlank(), onClick = { model.comment(userId, note); note = "" },
                                modifier = Modifier.size(MinTouchTarget)) {
                                Icon(painterResource(R.drawable.ic_paper_plane), stringResource(R.string.reading_send))
                            }
                        })
            }
            if (submission != null) {
                Button(onClick = {}, enabled = false, shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.homework_turn_in_complete))
                }
            } else {
                Button(onClick = { adding = true }, enabled = !busy, shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.homework_add_work))
                }
                if (full || draft != null) OutlinedButton(onClick = { confirming = true }, enabled = draft != null && !busy,
                    shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(R.string.homework_turn_in))
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LaraColors.Surface, shape = LaraShapes.extraLarge) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Text(stringResource(R.string.homework_add_work), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { adding = false; camera = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(24.dp))
                Spacer(Modifier.width(LaraSpacing.S3))
                val hasPhoto = !path.isNullOrEmpty() && HomeworkAttachment.type(File(path)) == "IMAGE"
                Text(stringResource(if (hasPhoto) R.string.homework_retake else R.string.homework_take_photo))
            }
            TextButton(onClick = { adding = false; gallery.launch("image/*") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Icon(painterResource(R.drawable.ic_image), null, Modifier.size(24.dp))
                Spacer(Modifier.width(LaraSpacing.S3))
                Text(stringResource(R.string.homework_choose_photo))
            }
            TextButton(onClick = {
                adding = false
                documents.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(24.dp))
                Spacer(Modifier.width(LaraSpacing.S3))
                Text(stringResource(R.string.homework_attach_document))
            }
        }
    }
    if (confirming) org.lara.app.ui.components.NativeConfirmDialog(stringResource(R.string.homework_confirm_title), stringResource(R.string.homework_confirm_photo),
        stringResource(R.string.homework_turn_in), stringResource(R.string.enrollment_cancel_action),
        { confirming = false; if (draft != null && !busy) model.turnIn() }, { confirming = false })
}

@Composable
private fun HomeworkAttachmentPreview(path: String?, root: File) {
    if (path.isNullOrEmpty()) {
        Text(stringResource(R.string.homework_photo_hint), style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
        return
    }
    val file = remember(path) { File(path) }
    val isPhoto = HomeworkAttachment.type(file) == "IMAGE"
    val bitmap = if (isPhoto) rememberHomeworkBitmap(path, root) else null
    Surface(shape = LaraShapes.medium, color = LaraColors.Surface, border = BorderStroke(1.dp, LaraColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.homework_photo_description),
                Modifier.size(56.dp).clip(LaraShapes.small), contentScale = ContentScale.Crop)
            else Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.size(40.dp), tint = LaraColors.PrimaryDark)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1)) {
                Text(stringResource(if (isPhoto) R.string.homework_photo_item else R.string.homework_document_item),
                    style = MaterialTheme.typography.titleSmall, color = LaraColors.TextPrimary)
                Text(if (isPhoto) stringResource(R.string.homework_photo_format)
                    else stringResource(R.string.homework_attachment_name, HomeworkAttachment.displayName(file)), style = MaterialTheme.typography.bodySmall,
                    color = LaraColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
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
