package org.lara.app.ui.screens.teacher.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import org.lara.app.AppContainer
import org.lara.app.R
import org.lara.app.data.repository.ClassGradebook as Gradebook
import org.lara.app.ui.components.LoadingSpinner
import org.lara.app.ui.theme.LaraColors
import org.lara.app.ui.theme.LaraShapes
import org.lara.app.ui.theme.LaraSpacing

private val LearnerColumnWidth = 128.dp
private val GradeColumnWidth = 112.dp

@Composable
internal fun ClassGradebook(container: AppContainer, userId: String, classroomId: String, reachable: Boolean) {
    LaunchedEffect(container, userId, reachable) {
        if (reachable) {
            try {
                container.sync.refreshLegacyGradeReceipts(userId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep displaying saved grades if the refresh fails.
            }
        }
    }
    val book by remember(container, classroomId) { container.gradebook(classroomId) }.collectAsStateWithLifecycle(null)
    val data = book
    Column(Modifier.fillMaxSize().padding(LaraSpacing.S4), verticalArrangement = Arrangement.spacedBy(LaraSpacing.S4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S2)) {
            Icon(painterResource(R.drawable.ic_exam_regular), null, Modifier.size(24.dp), tint = LaraColors.PrimaryDark)
            Text(stringResource(R.string.nav_grades), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        }
        when {
            data == null -> LoadingSpinner()
            data.learners.isEmpty() -> Text(stringResource(R.string.parity_grades_empty))
            else -> {
                if (data.work.isEmpty()) Text(stringResource(R.string.parity_grades_no_work))
                GradeTable(data)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GradeTable(data: Gradebook) {
    // The header and each score row have the same viewport and content width.
    val scoreScroll = rememberScrollState()
    val learnerLabel = stringResource(R.string.parity_learner)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LaraShapes.medium,
        color = LaraColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, LaraColors.Border),
    ) {
        LazyColumn {
            stickyHeader {
                Column(Modifier.background(LaraColors.SurfaceSubtle)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            learnerLabel,
                            Modifier.width(LearnerColumnWidth).padding(LaraSpacing.S3).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(Modifier.weight(1f).horizontalScroll(scoreScroll)) {
                            data.work.forEach { work ->
                                Column(
                                    Modifier.width(GradeColumnWidth).padding(LaraSpacing.S3)
                                        .semantics(mergeDescendants = true) { heading() },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(LaraSpacing.S1),
                                ) {
                                    Text(work.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                                    work.points?.let {
                                        Text(
                                            stringResource(R.string.parity_grade_points, it),
                                            color = LaraColors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = LaraColors.Border)
                }
            }
            items(data.learners, key = { it.id }) { learner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        learner.name ?: learnerLabel,
                        Modifier.width(LearnerColumnWidth).padding(LaraSpacing.S3),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(Modifier.weight(1f).horizontalScroll(scoreScroll)) {
                        data.work.forEach { work ->
                            val score = learner.scores[work.id]
                            Text(
                                score?.toString() ?: stringResource(R.string.parity_grade_missing),
                                Modifier.width(GradeColumnWidth).padding(LaraSpacing.S3),
                                color = if (score == null) LaraColors.TextSecondary else LaraColors.TextPrimary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                HorizontalDivider(color = LaraColors.Border)
            }
        }
    }
}
