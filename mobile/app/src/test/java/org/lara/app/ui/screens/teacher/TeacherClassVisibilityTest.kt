package org.lara.app.ui.screens.teacher

import org.junit.Test
import org.junit.Assert.*
import org.lara.app.domain.model.Classroom

class TeacherClassVisibilityTest {
    @Test fun onlyOwnedOrCoTaughtUnarchivedClassesExposeApprovalControls() {
        val classroom = Classroom("class", "Science", "A", "K7M4QX", "owner", null, null, 1, 1)
        assertTrue(classroom.managedBy("owner"))
        assertFalse(classroom.managedBy("other"))
        assertTrue(classroom.copy(coTeacherIdsJson = """["other"]""").managedBy("other"))
        assertFalse(classroom.copy(coTeacherIdsJson = "malformed").managedBy("other"))
        assertFalse(classroom.copy(archivedAt = 2).managedBy("owner"))
    }
}
