package org.lara.app.ui.screens.teacher

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
internal fun ApprovalLoadingSkeleton() {
    val label = stringResource(R.string.approval_loading)
    val transition = rememberInfiniteTransition(label = "approval-loading")
    val opacity by transition.animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "approval-loading-opacity")
    Column(Modifier.fillMaxWidth().semantics {
        contentDescription = label
        liveRegion = LiveRegionMode.Polite
    }, verticalArrangement = Arrangement.spacedBy(LaraSpacing.S3)) {
        Spacer(Modifier.fillMaxWidth(0.7f).height(LaraSpacing.S6).alpha(opacity)
            .background(LaraColors.Border, LaraShapes.medium))
        Spacer(Modifier.fillMaxWidth().height(LaraSpacing.S12).alpha(opacity)
            .background(LaraColors.Border, LaraShapes.medium))
    }
}
