package org.lara.app.ui.screens.homework

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.min
import org.lara.app.R
import org.lara.app.data.repository.DocumentScanner
import org.lara.app.ui.theme.*

@Composable
fun DocumentReviewScreen(source: String, preview: String?, root: File, initialCorners: FloatArray,
    initialEnhance: Boolean, detected: Boolean, available: Boolean, busy: Boolean, failed: Boolean,
    previewScan: (FloatArray, Boolean) -> Unit, adjust: () -> Unit, usePreview: () -> Unit,
    useFullPhoto: () -> Unit, cancel: () -> Unit) {
    var corners by rememberSaveable(source) { mutableStateOf(initialCorners.copyOf()) }
    var enhance by rememberSaveable(source) { mutableStateOf(initialEnhance) }
    var selected by rememberSaveable(source) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val bitmap = rememberHomeworkBitmap(preview ?: source, root)
    val labels = listOf(R.string.scan_top_left, R.string.scan_top_right, R.string.scan_bottom_right, R.string.scan_bottom_left)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            IconButton(onClick = cancel, enabled = !busy, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back))
            }
            Text(stringResource(if (preview == null) R.string.scan_review else R.string.scan_preview), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(when {
            !available -> R.string.scan_unavailable
            preview != null && detected -> R.string.scan_check_preview
            preview != null -> R.string.scan_full_preview
            detected -> R.string.scan_detected
            else -> R.string.scan_manual
        }))
        if (bitmap == null) org.lara.app.ui.screens.stream.ReadingSkeleton()
        else Box(Modifier.fillMaxWidth().height(320.dp)) {
            Image(bitmap.asImageBitmap(), stringResource(R.string.homework_photo_description),
                Modifier.fillMaxSize().padding(if (preview == null && available) MinTouchTarget / 2 else 0.dp), contentScale = ContentScale.Fit)
            if (preview == null && available) {
                val touchRadius = with(LocalDensity.current) { (MinTouchTarget / 2).toPx() }
                val description = stringResource(R.string.scan_drag_corners)
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(bitmap, busy) {
                    fun bounds(): Rect {
                        val scale = min((size.width - touchRadius * 2).coerceAtLeast(1f) / bitmap.width,
                            (size.height - touchRadius * 2).coerceAtLeast(1f) / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        return Rect((size.width - width) / 2, (size.height - height) / 2,
                            (size.width + width) / 2, (size.height + height) / 2)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (busy) return@awaitEachGesture
                        val frame = bounds()
                        val index = (0..3).minBy { index ->
                            (Offset(frame.left + corners[index * 2] * frame.width, frame.top + corners[index * 2 + 1] * frame.height) - down.position).getDistance()
                        }
                        val point = Offset(frame.left + corners[index * 2] * frame.width, frame.top + corners[index * 2 + 1] * frame.height)
                        if ((point - down.position).getDistance() > touchRadius) return@awaitEachGesture
                        selected = index
                        down.consume()
                        drag(down.id) { change ->
                            corners = corners.copyOf().apply {
                                this[index * 2] = ((change.position.x - frame.left) / frame.width).coerceIn(0f, 1f)
                                this[index * 2 + 1] = ((change.position.y - frame.top) / frame.height).coerceIn(0f, 1f)
                            }
                            change.consume()
                        }
                    }
                }) {
                    val scale = min((size.width - touchRadius * 2).coerceAtLeast(1f) / bitmap.width,
                        (size.height - touchRadius * 2).coerceAtLeast(1f) / bitmap.height)
                    val left = (size.width - bitmap.width * scale) / 2
                    val top = (size.height - bitmap.height * scale) / 2
                    val points = (0..3).map { Offset(left + corners[it * 2] * bitmap.width * scale, top + corners[it * 2 + 1] * bitmap.height * scale) }
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, LaraColors.PrimaryDark, style = Stroke(2.dp.toPx()))
                    points.forEachIndexed { index, point ->
                        drawCircle(LaraColors.Surface, (if (index == selected) 12.dp else 8.dp).toPx(), point)
                        drawCircle(LaraColors.PrimaryDark, (if (index == selected) 12.dp else 8.dp).toPx(), point, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.scan_processing))
        }
        if (failed) Text(stringResource(R.string.scan_failed))
        if (preview == null && available) {
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.scan_selected_corner, stringResource(labels[selected])))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    labels.forEachIndexed { index, label -> DropdownMenuItem(
                        text = { Text(stringResource(label)) }, onClick = { selected = index; menu = false },
                        modifier = Modifier.heightIn(min = MinTouchTarget)) }
                }
            }
            Text(stringResource(R.string.scan_horizontal))
            val horizontalLabel = stringResource(R.string.scan_horizontal)
            Slider(value = corners[selected * 2], enabled = !busy, onValueChange = { value -> corners = corners.copyOf().apply { this[selected * 2] = value } },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { contentDescription = horizontalLabel })
            Text(stringResource(R.string.scan_vertical))
            val verticalLabel = stringResource(R.string.scan_vertical)
            Slider(value = corners[selected * 2 + 1], enabled = !busy, onValueChange = { value -> corners = corners.copyOf().apply { this[selected * 2 + 1] = value } },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics { contentDescription = verticalLabel })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.scan_enhance), Modifier.weight(1f))
                val enhanceLabel = stringResource(R.string.scan_enhance)
                Switch(checked = enhance, enabled = !busy, onCheckedChange = { enhance = it }, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                    .semantics { contentDescription = enhanceLabel })
            }
            if (!DocumentScanner.valid(corners)) Text(stringResource(R.string.scan_invalid_corners))
            Button(onClick = { previewScan(corners.copyOf(), enhance) }, enabled = !busy && DocumentScanner.valid(corners),
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.scan_preview)) }
        }
        if (preview != null) {
            Button(onClick = usePreview, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.scan_use)) }
            OutlinedButton(onClick = adjust, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.scan_adjust)) }
        }
        OutlinedButton(onClick = useFullPhoto, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget)) { Text(stringResource(R.string.scan_full_photo)) }
        OutlinedButton(onClick = cancel, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.scan_cancel)) }
    }
}
