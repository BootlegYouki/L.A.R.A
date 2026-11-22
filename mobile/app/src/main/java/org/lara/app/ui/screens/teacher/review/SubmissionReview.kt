package org.lara.app.ui.screens.teacher.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.data.local.entities.AssignmentSubmissionEntity
import org.lara.app.data.remote.HubFailure
import org.lara.app.domain.Cached
import org.lara.app.domain.model.Assignment
import org.lara.app.ui.components.AttachmentChip
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.data.local.entities.attachments
import org.lara.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubmissionReview(container: AppContainer, userId: String, assignment: Assignment, reachable: Boolean,
    archived: Boolean, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val roster by remember(container, assignment.classroomId) { container.enrollments.roster(assignment.classroomId, scope) }.collectAsStateWithLifecycle()
    val cachedSubmissions by remember(container, assignment.id) { container.reviewSubmissions(assignment.id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val queuedGrades by remember(container) { container.gradeOutbox.observe() }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val submissions = cachedSubmissions.orEmpty()
    var access by remember(assignment.id) { mutableStateOf<Int?>(null) } // 0 allowed, 1 denied
    var online by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Int?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var gradeMessage by remember(selectedId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(gradeMessage) {
        if (gradeMessage == R.string.review_saved) {
            delay(4_000)
            gradeMessage = null
        }
    }
    var reviewedTab by rememberSaveable(assignment.id) { mutableStateOf(false) }
    var score by rememberSaveable(selectedId) { mutableStateOf("") }
    var feedback by rememberSaveable(selectedId) { mutableStateOf("") }
    var baselineScore by rememberSaveable(selectedId) { mutableStateOf("") }
    var baselineFeedback by rememberSaveable(selectedId) { mutableStateOf("") }
    var initialized by rememberSaveable(selectedId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = score != baselineScore || feedback != baselineFeedback
    fun navigate(action: () -> Unit) { if (!busy) { if (dirty) { pending = action; discard = true } else action() } }
    BackHandler { if (selectedId != null) navigate { selectedId = null } else close() }
    LaunchedEffect(assignment.id, reachable, retry) {
        message = null
        online = false
        access = if (container.reviewDenied(assignment.id)) 1 else 0
        if (reachable) {
            access = null
            try {
                container.gradingApi.list(userId, assignment.id)
                container.setReviewDenied(assignment.id, false)
                access = 0
                try { container.sync.pull(userId); online = true }
                catch (e: CancellationException) { throw e }
                catch (e: HubFailure) {
                    if (e.status == 403) { container.setReviewDenied(assignment.id, true); access = 1; online = false; selectedId = null }
                    else { online = false; message = R.string.review_refresh_failed }
                } catch (_: Exception) { online = false; message = R.string.review_refresh_failed }
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) {
                if (e.status == 403) { container.setReviewDenied(assignment.id, true); access = 1; selectedId = null }
                else { access = if (container.reviewDenied(assignment.id)) 1 else 0; message = R.string.review_offline }
            } catch (_: Exception) { access = if (container.reviewDenied(assignment.id)) 1 else 0; message = R.string.review_offline }
        }
    }
    val learners = (roster as? Cached.Content)?.value.orEmpty().filter { it.enrollment.status == "ACTIVE" && it.user != null }
        .sortedWith(compareBy({ it.user?.fullName?.lowercase().orEmpty() }, { it.enrollment.studentId }))
    val selected = submissions.find { it.id == selectedId }
    val pendingGrade = queuedGrades[selectedId]
    var previewIndex by rememberSaveable(selectedId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedId, selected) {
        if (!initialized && selected != null) {
            score = (pendingGrade?.score ?: selected.score)?.toString().orEmpty()
            feedback = pendingGrade?.feedback ?: selected.teacherFeedback.orEmpty()
            baselineScore = score; baselineFeedback = feedback; initialized = true
        }
    }
    Column(Modifier.fillMaxSize().background(LaraColors.Canvas).safeDrawingPadding().imePadding().padding(LaraSpacing.S4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedId != null) navigate { selectedId = null } else close() }, modifier = Modifier.size(MinTouchTarget), enabled = !busy) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.review_back))
            }
            Text(assignment.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        if (access == null) { ReviewSkeleton(); return@Column }
        if (access == 1) { Text(stringResource(R.string.review_forbidden), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }); return@Column }
        if (!online) {
            Text(stringResource(R.string.review_offline), color = LaraColors.TextSecondary)
            if (reachable) TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_retry)) }
        }
        message?.let { Text(stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        run {
            if (roster is Cached.Loading || cachedSubmissions == null) { ReviewSkeleton(); return@Column }
            val submissionsByLearner = submissions.associateBy { it.studentId }
            val submitted = learners.mapNotNull { learner ->
                submissionsByLearner[learner.enrollment.studentId]?.let { learner to it }
            }
            val (toReview, reviewed) = submitted.partition { (_, submission) ->
                submission.score == null && queuedGrades[submission.id]?.rejected != false
            }
            val turnedInCount = toReview.size
            val gradedCount = reviewed.size
            val missingCount = (learners.size - turnedInCount - gradedCount).coerceAtLeast(0)
            val rows = if (reviewedTab) reviewed else toReview
            Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.review_assignment_summary), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.review_summary_counts, turnedInCount, missingCount, gradedCount),
                    color = LaraColors.TextSecondary, modifier = Modifier.padding(top = LaraSpacing.S1, bottom = LaraSpacing.S3))
                TabRow(selectedTabIndex = if (reviewedTab) 1 else 0) {
                    Tab(selected = !reviewedTab, onClick = { reviewedTab = false },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        text = { Text(stringResource(R.string.review_to_review_tab, turnedInCount)) })
                    Tab(selected = reviewedTab, onClick = { reviewedTab = true },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        text = { Text(stringResource(R.string.review_reviewed_tab, gradedCount)) })
                }
                Spacer(Modifier.height(LaraSpacing.S3))
                if (rows.isEmpty()) {
                    Text(stringResource(if (reviewedTab) R.string.review_reviewed_empty else R.string.review_to_review_empty),
                        style = MaterialTheme.typography.bodyLarge)
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    items(rows, key = { (_, submission) -> submission.id }) { (learner, submission) ->
                        val queued = queuedGrades[submission.id]
                        val displayedScore = queued?.takeUnless { it.rejected }?.score ?: submission.score
                        val status = when {
                            queued?.rejected == true -> R.string.review_grade_needs_retry
                            queued != null -> R.string.review_grade_queued
                            displayedScore != null -> R.string.review_graded
                            else -> R.string.review_turned_in
                        }
                        Surface(Modifier.fillMaxWidth(), color = LaraColors.Surface, shape = LaraShapes.medium,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LaraColors.Border)) {
                            TextButton(onClick = { selectedId = submission.id },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = LaraSpacing.S2)) {
                                Icon(painterResource(if (displayedScore != null) R.drawable.ic_check_circle else R.drawable.ic_info),
                                    null, tint = if (displayedScore != null) LaraColors.Success else LaraColors.TextSecondary)
                                Spacer(Modifier.width(LaraSpacing.S3))
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(learner.user?.fullName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(status), color = LaraColors.TextSecondary)
                                }
                                displayedScore?.let { Text(stringResource(R.string.review_points_value, it, assignment.maxPoints), color = LaraColors.TextPrimary) }
                            }
                        }
                    }
                }
            }
        }
        if (selected != null) {
            val learner = learners.find { it.enrollment.studentId == selected.studentId }
            val ordered = learners.mapNotNull { person -> submissions.find { it.studentId == person.enrollment.studentId } }
            val index = ordered.indexOfFirst { it.id == selected.id }
            val confirmSheetState by rememberUpdatedState<(SheetValue) -> Boolean> { target ->
                if (target != SheetValue.Hidden) true
                else if (busy) false
                else if (dirty) {
                    navigate { selectedId = null }
                    false
                } else true
            }
            ModalBottomSheet(
                onDismissRequest = { navigate { selectedId = null } },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
                    confirmValueChange = { confirmSheetState(it) }),
            ) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(learner?.user?.fullName ?: stringResource(R.string.review_learner),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton({ navigate { selectedId = null } }, Modifier.size(MinTouchTarget), enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.review_back))
                    }
                }
                val attachments = remember(selected) { selected.attachments() }
                attachments.forEachIndexed { index, attachment ->
                    val forbidden = { access = 1; scope.launch { container.setReviewDenied(assignment.id, true) }; Unit }
                    val preview: (@Composable () -> Unit)? = if (attachment.fileType == "IMAGE") {
                        { key(selected.id, index) { ReviewPhoto(container, userId, selected, online, index, forbidden, compact = true) } }
                    } else null
                    AttachmentChip(attachment.fileType, attachment.name,
                        Modifier.clickable { previewIndex = if (previewIndex == index) null else index }, preview)
                    if (previewIndex == index) key(selected.id, index) {
                        if (attachment.fileType == "IMAGE") ReviewPhoto(container, userId, selected, online, index, forbidden)
                        else ReviewDocument(container, userId, selected.id, online, index, forbidden)
                    }
                }
                OutlinedTextField(score, { score = it }, label = { Text(stringResource(R.string.review_score, assignment.maxPoints)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !archived && !busy,
                    isError = score.isNotEmpty() && validatedScore(score, assignment.maxPoints) == null, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(),
                    supportingText = { if (score.isNotEmpty() && validatedScore(score, assignment.maxPoints) == null) Text(stringResource(R.string.review_invalid)) })
                OutlinedTextField(feedback, { feedback = it }, label = { Text(stringResource(R.string.review_feedback)) }, enabled = !archived && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll(), maxLines = 4)
                Button(onClick = {
                    val points = validatedScore(score, assignment.maxPoints) ?: return@Button
                    busy = true
                    gradeMessage = null
                    scope.launch {
                        try {
                            container.gradeOutbox.save(selected, points, feedback)
                            baselineScore = score
                            baselineFeedback = feedback
                            if (online) {
                                try {
                                    container.gradeOutbox.flush()
                                    gradeMessage = R.string.review_saved
                                    container.sync.pull(userId)
                                } catch (error: CancellationException) { throw error }
                                catch (_: Exception) { online = false }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { gradeMessage = R.string.review_save_failed }
                        finally { busy = false }
                    }
                }, enabled = initialized && !archived && !busy && (dirty || pendingGrade?.rejected == true) &&
                    validatedScore(score, assignment.maxPoints) != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) {
                    Text(stringResource(when {
                        busy -> R.string.review_saving
                        pendingGrade?.rejected == true -> R.string.review_grade_retry
                        else -> R.string.review_save
                    }))
                }
                pendingGrade?.let {
                    Text(stringResource(if (it.rejected) R.string.review_grade_needs_retry else R.string.review_grade_queued),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (it.rejected) MaterialTheme.colorScheme.error else LaraColors.TextSecondary)
                }
                gradeMessage?.takeIf { pendingGrade == null }?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (it == R.string.review_saved) LaraColors.PrimaryDark else MaterialTheme.colorScheme.error,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { navigate { selectedId = ordered[index - 1].id } }, enabled = index > 0 && !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_previous)) }
                    OutlinedButton(onClick = { navigate { selectedId = ordered[index + 1].id } }, enabled = index >= 0 && index < ordered.lastIndex && !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_next)) }
                }
            }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false; pending = null }, title = { Text(stringResource(R.string.review_discard_title)) },
        text = { Text(stringResource(R.string.review_discard_text)) },
        confirmButton = { TextButton(onClick = { discard = false; pending?.invoke(); pending = null }, modifier = Modifier.heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.review_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false; pending = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_keep)) } })
}

@Composable
internal fun ReviewPhoto(container: AppContainer, userId: String, submission: AssignmentSubmissionEntity, online: Boolean, attachment: Int, forbidden: () -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    var bitmap by remember(submission.id) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(submission.id) { mutableStateOf(false) }
    var loading by remember(submission.id) { mutableStateOf(false) }
    var retry by remember(submission.id) { mutableIntStateOf(0) }
    var scale by remember(submission.id) { mutableFloatStateOf(1f) }
    var offset by remember(submission.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(submission.id, online, retry) {
        bitmap = null; failure = false
        if (online) {
            loading = true
            var temp: File? = null
            try {
                val tempFile = File.createTempFile("review-", ".image", context.cacheDir)
                temp = tempFile
                container.gradingApi.file(userId, submission.id, tempFile, attachment = attachment)
                bitmap = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tempFile.path, bounds)
                    var sample = 1
                    val maximum = if (compact) 160 else 1600
                    while (bounds.outWidth / sample > maximum || bounds.outHeight / sample > maximum) sample *= 2
                    BitmapFactory.decodeFile(tempFile.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                failure = bitmap == null
            } catch (e: CancellationException) { throw e }
            catch (e: HubFailure) { if (e.status == 403) forbidden() else failure = true }
            catch (_: Exception) { failure = true }
            finally { temp?.delete(); loading = false }
        }
    }
    if (compact) {
        val photo = bitmap
        if (photo != null) Image(photo.asImageBitmap(), null, Modifier.size(MinTouchTarget), contentScale = ContentScale.Crop)
        else Icon(painterResource(R.drawable.ic_image), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
        return
    }
    when {
        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        bitmap != null -> Column {
            Image(requireNotNull(bitmap).asImageBitmap(), stringResource(R.string.review_photo), modifier = Modifier.fillMaxWidth().height(300.dp).clipToBounds()
                .pointerInput(submission.id) { detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    val limitX = size.width * (scale - 1f) / 2f
                    val limitY = size.height * (scale - 1f) / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                } }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                OutlinedButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }, enabled = scale > 1f,
                    modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_zoom_out)) }
                OutlinedButton(onClick = { scale = (scale * 1.5f).coerceAtMost(4f) }, enabled = scale < 4f,
                    modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_zoom_in)) }
            }
        }
        failure -> Column {
            Text(stringResource(R.string.review_photo_error))
            TextButton(onClick = { retry++ }, enabled = online, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.review_retry_photo)) }
        }
        else -> Text(stringResource(R.string.review_photo_offline))
    }
}

@Composable
private fun ReviewSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.review_loading))
        repeat(4) { Surface(Modifier.fillMaxWidth().height(72.dp), color = LaraColors.Border, shape = LaraShapes.medium) {} }
    }
}
