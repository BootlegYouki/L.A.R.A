package org.lara.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 4dp base grid. Every padding, gap and margin is one of these. Mobile screen gutter is 16dp.
object LaraSpacing {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 20.dp
    val S6 = 24.dp
    val S8 = 32.dp
    val S12 = 48.dp
}

// small 8: badges and tags. medium 12: buttons, inputs, quiz options. large 16: cards and toasts.
// extraLarge 24: dialogs and the top corners of bottom sheets. Pills use CircleShape or RoundedCornerShape(50).
val LaraShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Minimum interactive size for learner-facing controls. Primary actions and quiz options use 56dp.
val MinTouchTarget = 52.dp
val PrimaryTouchTarget = 56.dp

// Compose elevation is a single blur, so tint it with navy instead of gray. Ambient and spot colors are
// only honored on Android 9 (API 28) and above; on Android 8 phones the shadow stays neutral, which is acceptable.
fun Modifier.laraShadow(elevation: Dp = 2.dp, shape: Shape = LaraShapes.large): Modifier =
    this.shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Color(0x1417213D),
        spotColor = Color(0x2017213D),
    )
