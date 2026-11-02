package org.lara.app.data

import org.junit.Test
import org.junit.Assert.*
import org.lara.app.domain.model.Classroom
import org.lara.app.ui.screens.teacher.classes.canManage

class TeacherClassPolicyTest {
    private val classroom = Classroom("class", "Science", "A", "K7M4QX", "owner", "[\"co\"]", null, 1, 1)
    @Test fun onlyOwnerCanEditSettingsButBothTeachersCanRemoveLearners() {
        assertTrue(classroom.canManage("owner", true))
        assertFalse(classroom.canManage("co", true))
        assertTrue(classroom.canManage("co", false))
        assertFalse(classroom.canManage("stranger", false))
    }
    @Test fun archiveMakesAllClassCommandsReadOnly() {
        assertFalse(classroom.copy(archivedAt = 2).canManage("owner", true))
        assertFalse(classroom.copy(archivedAt = 2).canManage("co", false))
        assertFalse(classroom.copy(coTeacherIdsJson = "bad-cache").canManage("co", false))
    }
}
