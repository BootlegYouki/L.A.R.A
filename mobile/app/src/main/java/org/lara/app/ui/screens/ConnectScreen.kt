package org.lara.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import org.lara.app.R
import org.lara.app.ui.components.LaraPrimaryButton
import org.lara.app.ui.theme.LaraSpacing
import org.lara.app.ui.theme.LaraTheme

/**
 * Landing/connection screen the app opens to. It never shows a blocking "No Connection" dialog
 * (README §0.9); it simply invites the pupil or teacher to find the classroom Hub. The real mDNS
 * discovery + manual IP flow lands in #4; here the primary action is a wired-up placeholder.
 */
@Composable
fun ConnectScreen(
    onFindHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LaraSpacing.S6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.connect_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = LaraSpacing.S3),
        )
        LaraPrimaryButton(
            text = stringResource(R.string.connect_action),
            onClick = onFindHub,
            modifier = Modifier
                .padding(top = LaraSpacing.S8)
                .fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectScreenPreview() {
    LaraTheme { ConnectScreen(onFindHub = {}) }
}
