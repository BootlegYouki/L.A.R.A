package org.lara.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import org.lara.app.R
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.LaraTheme

/**
 * Shared placeholder body for the scaffold's bottom-nav destinations. Each real screen (Stream #25,
 * Classwork #25/#59, Quizzes #26, Tutor #27) replaces its placeholder in a later sprint. Uses only
 * theme typography and the 4dp spacing grid; no raw hex, no hard-coded strings.
 */
@Composable
private fun PlaceholderBody(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LaraSpacing.S4),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = LaraSpacing.S2),
        )
    }
}

@Composable
fun StreamScreen(modifier: Modifier = Modifier) = PlaceholderBody(
    title = stringResOf(R.string.placeholder_stream_title),
    body = stringResOf(R.string.placeholder_stream_body),
    modifier = modifier,
)

@Composable
fun ClassworkScreen(modifier: Modifier = Modifier) = PlaceholderBody(
    title = stringResOf(R.string.placeholder_classwork_title),
    body = stringResOf(R.string.placeholder_classwork_body),
    modifier = modifier,
)

@Composable
fun QuizzesScreen(modifier: Modifier = Modifier) = PlaceholderBody(
    title = stringResOf(R.string.placeholder_quizzes_title),
    body = stringResOf(R.string.placeholder_quizzes_body),
    modifier = modifier,
)

@Composable
fun TutorScreen(modifier: Modifier = Modifier) = PlaceholderBody(
    title = stringResOf(R.string.placeholder_tutor_title),
    body = stringResOf(R.string.placeholder_tutor_body),
    modifier = modifier,
)

/** Thin wrapper so the screens above read clearly; delegates to Compose's stringResource. */
@Composable
private fun stringResOf(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

@Preview(showBackground = true)
@Composable
private fun StreamScreenPreview() {
    LaraTheme { StreamScreen() }
}
