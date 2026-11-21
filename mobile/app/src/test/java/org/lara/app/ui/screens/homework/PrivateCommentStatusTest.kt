package org.lara.app.ui.screens.homework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.lara.app.R

class PrivateCommentStatusTest {
    @Test fun deliveryStatesUseDistinctLabels() {
        assertNull(privateCommentStatusLabel("SYNCED", null))
        assertEquals(R.string.reading_queued, privateCommentStatusLabel("QUEUED_FOR_SYNC", null))
        assertEquals(R.string.homework_comment_access_changed, privateCommentStatusLabel("REJECTED", "FORBIDDEN"))
        assertEquals(R.string.homework_comment_rejected, privateCommentStatusLabel("REJECTED", null))
    }
}
