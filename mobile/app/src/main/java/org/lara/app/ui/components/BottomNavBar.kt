package org.lara.app.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.lara.app.R
import org.lara.app.ui.navigation.ShellDestination
import org.lara.app.ui.theme.LaraColors

internal val ShellDestination.label: Int
    get() = when (this) {
        ShellDestination.STREAM -> R.string.nav_stream
        ShellDestination.CLASSWORK -> R.string.nav_classwork
        ShellDestination.QUIZZES -> R.string.nav_quizzes
        ShellDestination.AI_TUTOR -> R.string.nav_ai_tutor
    }

@Composable
fun BottomNavBar(
    selected: ShellDestination?,
    hasInProgressAttempt: Boolean,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.height(68.dp),
        containerColor = LaraColors.Surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        ShellDestination.entries.forEach { destination ->
            val active = destination == selected
            val locked = destination == ShellDestination.AI_TUTOR && hasInProgressAttempt
            val explanation = stringResource(R.string.ai_quiz_locked)
            val tint = if (destination == ShellDestination.AI_TUTOR) LaraColors.AiDark
                else LaraColors.PrimaryDark
            val icon = when (destination) {
                ShellDestination.STREAM -> if (active) R.drawable.ic_chats_fill else R.drawable.ic_chats_regular
                ShellDestination.CLASSWORK -> if (active) R.drawable.ic_books_fill else R.drawable.ic_books_regular
                ShellDestination.QUIZZES -> if (active) R.drawable.ic_exam_fill else R.drawable.ic_exam_regular
                ShellDestination.AI_TUTOR -> if (active) R.drawable.ic_sparkle_fill else R.drawable.ic_sparkle_regular
            }
            NavigationBarItem(
                selected = active,
                enabled = !locked,
                onClick = { onSelect(destination) },
                modifier = Modifier.height(68.dp).semantics {
                    if (locked) stateDescription = explanation
                },
                icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = { Text(stringResource(destination.label), maxLines = 2) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = tint,
                    selectedTextColor = tint,
                    indicatorColor = LaraColors.PrimaryLight,
                    unselectedIconColor = tint,
                    unselectedTextColor = LaraColors.TextSecondary,
                    disabledIconColor = LaraColors.TextSecondary,
                    disabledTextColor = LaraColors.TextSecondary,
                ),
            )
        }
    }
}
