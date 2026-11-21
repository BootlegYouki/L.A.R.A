package org.lara.app.ui.screens.homework

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
internal fun HomeworkCameraDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = LaraColors.Surface) { content() }
    }
}

@Composable
fun CameraCaptureScreen(onCaptured: (File) -> Unit, close: () -> Unit, document: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        var active = true
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (active) try {
                val camera = future.get()
                provider = camera
                preview.setSurfaceProvider(view.surfaceProvider)
                camera.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                ready = true
            } catch (_: Exception) { failed = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            ready = false
            provider?.unbind(preview, capture)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val previewHeight = (maxHeight * if (document) 0.60f else 0.66f).coerceIn(160.dp, 560.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S3),
            verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                IconButton(onClick = close, enabled = !busy, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back))
                }
                Text(stringResource(if (document) R.string.homework_scan_document else R.string.homework_camera_title), style = MaterialTheme.typography.titleLarge)
            }
            if (document) Text(
                stringResource(R.string.homework_frame),
                style = MaterialTheme.typography.bodyMedium,
                color = LaraColors.TextSecondary,
                modifier = Modifier.padding(horizontal = LaraSpacing.S3),
            )
            Box(
                Modifier.fillMaxWidth().height(previewHeight).clipToBounds().background(LaraColors.TextPrimary),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                if (document) Canvas(Modifier.fillMaxSize().padding(LaraSpacing.S5)) {
                    val arm = minOf(24.dp.toPx(), size.minDimension / 4)
                    val stroke = 3.dp.toPx()
                    for (x in listOf(0f, size.width)) {
                        for (y in listOf(0f, size.height)) {
                            val dx = if (x == 0f) arm else -arm
                            val dy = if (y == 0f) arm else -arm
                            val corner = Offset(x, y)
                            drawLine(LaraColors.Surface, corner, Offset(x + dx, y), stroke)
                            drawLine(LaraColors.Surface, corner, Offset(x, y + dy), stroke)
                        }
                    }
                }
                if (!ready && !failed) Surface(shape = LaraShapes.medium) {
                    Box(Modifier.padding(LaraSpacing.S3)) {
                        org.lara.app.ui.components.LoadingSpinner(inline = true)
                    }
                }
            }
            if (failed) Text(stringResource(R.string.homework_camera_failed))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
                onClick = {
                busy = true
                failed = false
                var file: File? = null
                try {
                    val output = File.createTempFile("homework-capture-", ".jpg", context.cacheDir)
                    file = output
                    capture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(), ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                busy = false
                                onCaptured(output)
                            }
                            override fun onError(error: ImageCaptureException) {
                                output.delete()
                                busy = false
                                failed = true
                            }
                        })
                } catch (_: Exception) {
                    file?.delete()
                    busy = false
                    failed = true
                }
            }) { Text(stringResource(if (document) R.string.homework_capture_document else R.string.homework_take_photo)) }
        }
    }
}
