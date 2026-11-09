package org.lara.app.ui.screens.teacher.classes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.lara.app.R
import org.lara.app.ui.components.ClassFormDialog
import org.lara.app.ui.components.DiscoveryBanner
import org.lara.app.ui.components.classFormInputScroll
import org.lara.app.ui.theme.*

@Composable
internal fun CreateClassDialog(
    name: String,
    section: String,
    review: Boolean,
    online: Boolean,
    busy: Boolean,
    onName: (String) -> Unit,
    onSection: (String) -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ClassFormDialog(
        title = stringResource(if (review) R.string.classes_review else R.string.classes_create),
        action = stringResource(if (review) R.string.classes_create_action else R.string.classes_review_action),
        enabled = online && !busy && name.isNotBlank() && section.isNotBlank(),
        onAction = if (review) onCreate else onReview, onDismiss = onDismiss,
    ) {
        if (!online) DiscoveryBanner(R.string.classes_offline, warning = true, success = false)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = LaraColors.PrimaryDark, trackColor = LaraColors.Border)
            Text(stringResource(R.string.classes_updating))
        }
        if (review) {
            Text(stringResource(R.string.classes_review_help))
            ClassInfoCard {
                Text(stringResource(R.string.classes_name), style = MaterialTheme.typography.labelLarge)
                Text(name.trim(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.classes_section), style = MaterialTheme.typography.labelLarge)
                Text(section.trim())
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.classes_edit))
            }
        } else {
            Text(stringResource(R.string.classes_create_help))
            Text(stringResource(R.string.classes_required_help), color = LaraColors.TextSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                val label = stringResource(R.string.classes_required_field, stringResource(R.string.classes_name))
                Text(label, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(name, onName, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll()
                    .semantics { contentDescription = label }, shape = LaraShapes.medium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                val label = stringResource(R.string.classes_required_field, stringResource(R.string.classes_section))
                Text(label, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(section, onSection, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).classFormInputScroll()
                    .semantics { contentDescription = label }, shape = LaraShapes.medium)
            }
        }
    }
}
