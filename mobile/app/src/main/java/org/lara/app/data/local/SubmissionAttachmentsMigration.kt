package org.lara.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SubmissionAttachmentsMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE assignment_submissions ADD COLUMN attachments_json TEXT NOT NULL DEFAULT '[]'")
    }
}
