package org.lara.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.ComponentDialog
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
fun ClassFormDialog(
    title: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    canDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val render: @Composable () -> Unit = {
        Scaffold(Modifier.fillMaxSize(), containerColor = LaraColors.Surface, topBar = {
            Surface(color = LaraColors.PrimaryDark, contentColor = LaraColors.Surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = LaraSpacing.S3, vertical = LaraSpacing.S1),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                    IconButton(onClick = onDismiss, enabled = canDismiss, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.enrollment_cancel_action), Modifier.size(24.dp))
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = PrimaryTouchTarget),
                        colors = ButtonDefaults.buttonColors(containerColor = LaraColors.PrimaryLight, contentColor = LaraColors.PrimaryDark,
                            disabledContainerColor = LaraColors.Border, disabledContentColor = LaraColors.TextMuted)) {
                        Text(action)
                    }
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(LaraSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(LaraSpacing.S6), content = content)
        }
    }
    val context = LocalContext.current
    val composition = rememberCompositionContext()
    val currentRender by rememberUpdatedState(render)
    val dismiss by rememberUpdatedState(onDismiss)
    val view = remember(context) { ComposeView(context).apply {
        setParentCompositionContext(composition)
        setContent { currentRender() }
    } }
    val dialog = remember(context) { ComponentDialog(context).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setOnCancelListener { dismiss() }
        setContentView(view)
    } }
    SideEffect { dialog.setCancelable(canDismiss) }
    DisposableEffect(dialog) {
        val window = requireNotNull(dialog.window)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(LaraColors.Surface.toArgb()))
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            view.disposeComposition()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.classFormInputScroll(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    val textHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toPx() }
    LaunchedEffect(focused, height, textHeight) {
        if (focused && height > 0) {
            delay(350)
            // A landscape keyboard can leave room for the text but not the whole padded field.
            val top = ((height - textHeight) / 2f).coerceAtLeast(0f)
            requester.bringIntoView(Rect(0f, top, 1f, top + textHeight))
        }
    }
    return bringIntoViewRequester(requester).onSizeChanged { height = it.height }.onFocusChanged { focused = it.hasFocus }
}
