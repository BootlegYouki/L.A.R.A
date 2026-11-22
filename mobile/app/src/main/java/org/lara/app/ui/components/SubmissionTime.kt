package org.lara.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Date
import org.lara.app.R
import org.lara.app.ui.theme.LaraColors

@Composable
internal fun SubmissionTime(submittedAt: Long, rejected: Boolean = false) {
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(submittedAt))
    Text(
        text = stringResource(if (rejected) R.string.assignment_attempted_at else R.string.assignment_turned_in_at, timestamp),
        style = MaterialTheme.typography.bodySmall,
        color = LaraColors.TextSecondary,
    )
}
