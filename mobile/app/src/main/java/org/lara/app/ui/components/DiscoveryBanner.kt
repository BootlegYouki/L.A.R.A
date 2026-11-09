package org.lara.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.theme.*

@Composable
fun DiscoveryBanner(message: Int, warning: Boolean, success: Boolean) {
    val fill = when { warning -> LaraColors.WarningLight; success -> LaraColors.PrimaryLight; else -> LaraColors.InfoLight }
    val ink = when { warning -> LaraColors.WarningBannerText; success -> LaraColors.PrimaryDark; else -> LaraColors.InfoDark }
    val border = when { warning -> LaraColors.WarningBorder; success -> LaraColors.SuccessBorder; else -> LaraColors.InfoBorder }
    val icon = when { warning -> R.drawable.ic_warning_circle; success -> R.drawable.ic_check_circle; else -> R.drawable.ic_info }
    val text = stringResource(message)
    Surface(
        color = fill, shape = LaraShapes.medium, border = BorderStroke(1.dp, border),
        modifier = Modifier.laraShadow(shape = LaraShapes.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(LaraSpacing.S4).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(LaraSpacing.S3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (warning) LaraColors.WarningDark else ink)
            Text(buildAnnotatedString {
                val colon = text.indexOf(':')
                if (colon >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(0, colon + 1)) }
                    append(text.substring(colon + 1))
                } else append(text)
            }, color = ink, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
