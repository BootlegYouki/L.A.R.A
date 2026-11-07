package org.lara.app.ui.screens.teacher.classes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.domain.model.Classroom
import org.lara.app.ui.theme.*

@Composable
internal fun ClassInfoCard(content: @Composable ColumnScope.() -> Unit) {
    org.lara.app.ui.components.ClassroomSurface(content = content)
}

@Composable
internal fun ManagedClassCard(row: Classroom, selected: Boolean, enabled: Boolean, open: () -> Unit) {
    Surface(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth().laraShadow(),
        shape = LaraShapes.large, color = LaraColors.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LaraColors.Primary else LaraColors.Border)) {
        Row(Modifier.padding(LaraSpacing.S4), horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S4),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = LaraShapes.medium, color = LaraColors.PrimaryDark) {
                Icon(painterResource(R.drawable.ic_books_regular), null, Modifier.padding(LaraSpacing.S4).size(LaraSpacing.S6), tint = LaraColors.Surface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(row.name, style = MaterialTheme.typography.titleLarge)
                Text(row.section, color = LaraColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(if (row.archivedAt == null) R.drawable.ic_check_circle else R.drawable.ic_info), null,
                        Modifier.size(LaraSpacing.S4), tint = LaraColors.PrimaryDark)
                    Text(stringResource(if (row.archivedAt == null) R.string.classes_active else R.string.classes_archived),
                        style = MaterialTheme.typography.labelLarge, color = LaraColors.PrimaryDark)
                }
            }
        }
    }
}

@Composable
internal fun ClassPersonCard(name: String, status: String, action: @Composable () -> Unit = {}) {
    ClassInfoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = LaraColors.PrimaryLight) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(name.trim().take(1).uppercase(), color = LaraColors.PrimaryDark, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = LaraColors.TextSecondary)
            }
        }
        action()
    }
}
