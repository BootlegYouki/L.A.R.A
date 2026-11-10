package org.lara.app.ui.screens.homework

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
fun CameraCaptureScreen(onCaptured: (File) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(granted, lifecycle) {
        var active = true
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        if (granted) {
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
        }
        onDispose {
            active = false
            ready = false
            provider?.unbind(preview, capture)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LaraSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            IconButton(onClick = close, enabled = !busy, modifier = Modifier.size(MinTouchTarget)) {
                Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.settings_back))
            }
            Text(stringResource(R.string.homework_camera_title), style = MaterialTheme.typography.titleLarge)
        }
        if (granted) Box(Modifier.fillMaxWidth().height(320.dp).clipToBounds().background(LaraColors.TextPrimary), contentAlignment = Alignment.Center) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().padding(LaraSpacing.S5)) {
                drawRect(LaraColors.Primary, topLeft = Offset.Zero, size = Size(size.width, size.height),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 8.dp.toPx()))))
            }
            Surface(Modifier.align(Alignment.TopCenter).padding(LaraSpacing.S3), color = LaraColors.TextPrimary) {
                Text(stringResource(R.string.homework_frame),
                    Modifier.padding(LaraSpacing.S2), color = LaraColors.Surface)
            }
        } else {
            Text(stringResource(R.string.homework_permission))
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = PrimaryTouchTarget)) {
                Text(stringResource(R.string.homework_allow_camera))
            }
        }
        if (failed) Text(stringResource(R.string.homework_camera_failed))
        if (granted && !ready && !failed) org.lara.app.ui.screens.stream.ReadingSkeleton()
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryTouchTarget),
            colors = ButtonDefaults.buttonColors(containerColor = LaraColors.Surface, contentColor = LaraColors.PrimaryDark),
            border = BorderStroke(4.dp, LaraColors.PrimaryLight), onClick = {
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
        }) { Text(stringResource(R.string.homework_take_photo)) }
        OutlinedButton(onClick = close, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.settings_back))
        }
    }
}
