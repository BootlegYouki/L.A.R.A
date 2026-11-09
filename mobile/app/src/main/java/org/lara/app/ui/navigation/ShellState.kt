package org.lara.app.ui.navigation

enum class ShellRole { STUDENT, TEACHER }

enum class ShellDestination { STREAM, CLASSWORK, QUIZZES, AI_TUTOR }

/** Supplied by authentication and Room-backed feature state; never persisted by navigation. */
data class ShellState(
    val role: ShellRole,
    val isHubReachable: Boolean = false,
    val isLoading: Boolean = false,
    val hasInProgressAttempt: Boolean = false,
) {
    fun allows(destination: ShellDestination): Boolean =
        destination != ShellDestination.AI_TUTOR || !hasInProgressAttempt
}
