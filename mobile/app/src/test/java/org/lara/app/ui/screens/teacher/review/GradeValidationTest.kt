package org.lara.app.ui.screens.teacher.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeValidationTest {
    @Test fun acceptsInclusiveBounds() {
        assertEquals(0L, validatedScore("0", 50))
        assertEquals(50L, validatedScore("50", 50))
    }

    @Test fun rejectsInvalidScores() {
        listOf("", "-1", "51", "1.5", "abc", " 9", "1e1", "+1", "9223372036854775808").forEach {
            assertNull(validatedScore(it, 50))
        }
        assertNull(validatedScore("0", -1))
    }
}
