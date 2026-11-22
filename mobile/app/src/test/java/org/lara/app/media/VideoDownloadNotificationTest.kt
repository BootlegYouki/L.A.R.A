package org.lara.app.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lara.app.domain.DownloadPhase
import org.lara.app.domain.VideoDownload
import org.lara.app.ui.screens.classwork.downloadNotificationPercent

class VideoDownloadNotificationTest {
    @Test fun receivedBytesNeverLookCompleteBeforePersistence() {
        assertEquals(99, downloadNotificationPercent(VideoDownload(DownloadPhase.DOWNLOADING, 1000, 1000)))
        assertEquals(99, downloadNotificationPercent(VideoDownload(DownloadPhase.PAUSED, 1000, 1000)))
        assertEquals(99, downloadNotificationPercent(VideoDownload(DownloadPhase.FAILED, 1000, 1000)))
    }

    @Test fun notificationReachesCompleteOnlyAfterRepositoryReportsComplete() {
        assertEquals(100, downloadNotificationPercent(VideoDownload(DownloadPhase.COMPLETE, 1000, 1000)))
        assertEquals(0, downloadNotificationPercent(VideoDownload(DownloadPhase.IDLE, 0, 1000)))
    }
}
