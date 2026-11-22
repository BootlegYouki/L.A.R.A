package org.lara.app.ui.screens.teacher.review

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.ui.components.LoadingSpinner
import org.lara.app.ui.theme.LaraSpacing

@Composable
internal fun ClassGradebook(container: AppContainer, userId: String, classroomId: String, reachable: Boolean) {
    LaunchedEffect(container, userId, reachable) {
        if (reachable) {
            try {
                container.sync.refreshLegacyGradeReceipts(userId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The saved gradebook is still available if the Hub cannot be reached.
            }
        }
    }
    val book by remember(container, classroomId) { container.gradebook(classroomId) }.collectAsStateWithLifecycle(null)
    val data = book
    Column(Modifier.fillMaxSize().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Text(stringResource(R.string.nav_grades), style = MaterialTheme.typography.headlineMedium)
        when {
            data == null -> LoadingSpinner()
            data.learners.isEmpty() -> Text(stringResource(R.string.parity_grades_empty))
            else -> {
                if (data.work.isEmpty()) Text(stringResource(R.string.parity_grades_no_work))
                LazyColumn(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    item {
                        Row {
                            GradeCell(stringResource(R.string.parity_learner))
                            data.work.forEach { column ->
                                GradeCell(column.points?.let { stringResource(R.string.parity_grade_column, column.title, it) } ?: column.title)
                            }
                        }
                    }
                    items(data.learners, key = { it.id }) { learner ->
                        HorizontalDivider()
                        Row {
                            GradeCell(learner.name ?: stringResource(R.string.parity_learner))
                            data.work.forEach { column ->
                                GradeCell(learner.scores[column.id]?.toString() ?: stringResource(R.string.parity_grade_missing))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeCell(value: String) {
    Text(value, Modifier.width(144.dp).padding(LaraSpacing.S2), style = MaterialTheme.typography.bodyMedium)
}
