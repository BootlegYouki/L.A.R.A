package org.lara.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.lara.app.ui.theme.MinTouchTarget
import org.lara.app.ui.theme.PrimaryTouchTarget

/**
 * Design-system buttons that bake in the pupil touch-target floor: every interactive control is at
 * least 52dp high, and a primary action is 56dp (design-system §3.2, invariants checklist). Feature
 * screens use these instead of a bare Material [Button] so the minimum can never be forgotten.
 */

/** Primary action (one per view). Enforces the 56dp preferred target. */
@Composable
fun LaraPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = PrimaryTouchTarget),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary / standard action. Enforces the 52dp minimum target. */
@Composable
fun LaraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
