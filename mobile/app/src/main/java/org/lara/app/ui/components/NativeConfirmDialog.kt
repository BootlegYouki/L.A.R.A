package org.lara.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Real platform (framework) confirm dialog; shown while in composition, dismissed when it leaves. */
@Composable
fun NativeConfirmDialog(title: String, message: String, confirm: String, cancel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val latestConfirm by rememberUpdatedState(onConfirm)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(title, message) {
        val dialog = android.app.AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton(confirm) { _, _ -> latestConfirm() }
            .setNegativeButton(cancel) { _, _ -> latestDismiss() }
            .setOnCancelListener { latestDismiss() }.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
