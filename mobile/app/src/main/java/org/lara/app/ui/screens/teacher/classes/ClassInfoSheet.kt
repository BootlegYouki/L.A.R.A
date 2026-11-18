package org.lara.app.ui.screens.teacher.classes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.lara.app.R
import org.lara.app.domain.enrollment.ClassCode
import org.lara.app.domain.model.Classroom
import org.lara.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

/** Read-only details of a class: name, section, join code and when it was created. */
@Composable
fun ClassInfoSheet(classroom: Classroom) {
    val code = ClassCode.canonicalOrNull(classroom.classCode)?.let { it.take(3) + "-" + it.drop(3) } ?: classroom.classCode
    Column(Modifier.fillMaxWidth().padding(horizontal = LaraSpacing.S4).padding(bottom = LaraSpacing.S4).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Text(classroom.name, style = MaterialTheme.typography.titleLarge)
        Text(classroom.section, style = MaterialTheme.typography.bodyLarge, color = LaraColors.TextSecondary)
        InfoRow(stringResource(R.string.class_info_code), code)
        InfoRow(stringResource(R.string.class_info_created), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(classroom.createdAt)))
    }
}

@Composable
private fun InfoRow(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = LaraColors.TextSecondary)
    Text(value, style = MaterialTheme.typography.titleMedium)
}
