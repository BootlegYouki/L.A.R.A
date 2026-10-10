package org.lara.app.ui.navigation

import androidx.annotation.StringRes
import org.lara.app.R

/**
 * Route constants for the scaffold. Kept as plain string constants (not an enum of the NavHost)
 * so later graphs (#24 StudentNavGraph / TeacherNavGraph) can split these without churn.
 */
object LaraRoutes {
    const val CONNECT = "connect"

    /** Outer-graph route for the bottom-nav shell; owns a single Scaffold + inner tab NavHost. */
    const val HOME = "home"

    const val STREAM = "stream"
    const val CLASSWORK = "classwork"
    const val QUIZZES = "quizzes"
    const val TUTOR = "tutor"
}

/**
 * The four placeholder bottom-navigation destinations that mirror the Student graph in the design
 * system (§5.13): Stream, Classwork, Quizzes, AI Tutor. Labels and accessible names are string
 * resources so English/Filipino parity holds (no hard-coded UI text).
 */
enum class LaraTab(
    val route: String,
    @StringRes val labelRes: Int,
) {
    STREAM(LaraRoutes.STREAM, R.string.nav_stream),
    CLASSWORK(LaraRoutes.CLASSWORK, R.string.nav_classwork),
    QUIZZES(LaraRoutes.QUIZZES, R.string.nav_quizzes),
    TUTOR(LaraRoutes.TUTOR, R.string.nav_tutor),
}
