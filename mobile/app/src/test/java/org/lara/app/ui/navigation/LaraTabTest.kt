package org.lara.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the navigation shell's invariants so a later refactor can't silently break the four-tab
 * Student layout the design system mandates (§5.13).
 */
class LaraTabTest {

    @Test
    fun fourBottomNavDestinations() {
        // Stream, Classwork, Quizzes, AI Tutor.
        assertEquals(4, LaraTab.entries.size)
    }

    @Test
    fun tabRoutesAreUniqueAndNonBlank() {
        val routes = LaraTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.isNotBlank() })
    }

    @Test
    fun tabsCoverTheExpectedRoutes() {
        val routes = LaraTab.entries.map { it.route }.toSet()
        assertEquals(
            setOf(
                LaraRoutes.STREAM,
                LaraRoutes.CLASSWORK,
                LaraRoutes.QUIZZES,
                LaraRoutes.TUTOR,
            ),
            routes,
        )
    }
}
