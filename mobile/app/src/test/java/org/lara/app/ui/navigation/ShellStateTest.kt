package org.lara.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellStateTest {
    @Test
    fun activeAttemptBlocksOnlyTutorForBothRolesEvenOffline() {
        ShellRole.entries.forEach { role ->
            val state = ShellState(role, isHubReachable = false, hasInProgressAttempt = true)
            assertFalse(state.allows(ShellDestination.AI_TUTOR))
            listOf(ShellDestination.STREAM, ShellDestination.CLASSWORK, ShellDestination.QUIZZES)
                .forEach { assertTrue(state.allows(it)) }
        }
    }

    @Test
    fun submittedAttemptAllowsTutorAgain() {
        val active = ShellState(ShellRole.STUDENT, hasInProgressAttempt = true)
        assertTrue(active.copy(hasInProgressAttempt = false).allows(ShellDestination.AI_TUTOR))
    }
}
