package org.lara.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Rebuild to backfill NOT NULL audit fields without leaving a synthetic default on new rows.
object AuditTimestampMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE `enrollments_audit` (`id` TEXT NOT NULL, `classroom_id` TEXT NOT NULL, `student_id` TEXT NOT NULL,
            `status` TEXT NOT NULL DEFAULT 'PENDING', `joined_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`classroom_id`) REFERENCES `classrooms`(`id`) ON
            UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `enrollments_audit` (`id`, `classroom_id`, `student_id`, `status`, `joined_at`, `updated_at`,
            `created_at`) SELECT `id`, `classroom_id`, `student_id`, `status`, `joined_at`, `updated_at`, `joined_at` FROM
            `enrollments`
        """.trimIndent())
        db.execSQL("DROP TABLE `enrollments`")
        db.execSQL("ALTER TABLE `enrollments_audit` RENAME TO `enrollments`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_client_enrollments_student` ON `enrollments` (`student_id` ASC, `status` ASC)")
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_enrollments_classroom_id_student_id` ON `enrollments` (`classroom_id`
            ASC, `student_id` ASC)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `material_chunks_audit` (`id` TEXT NOT NULL, `material_id` TEXT NOT NULL, `order_index` INTEGER NOT
            NULL, `heading` TEXT, `text` TEXT NOT NULL, `token_estimate` INTEGER, `updated_at` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`material_id`) REFERENCES `materials`(`id`) ON
            UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `material_chunks_audit` (`id`, `material_id`, `order_index`, `heading`, `text`, `token_estimate`,
            `updated_at`, `created_at`) SELECT `id`, `material_id`, `order_index`, `heading`, `text`, `token_estimate`,
            `updated_at`, `updated_at` FROM `material_chunks`
        """.trimIndent())
        db.execSQL("DROP TABLE `material_chunks`")
        db.execSQL("ALTER TABLE `material_chunks_audit` RENAME TO `material_chunks`")
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_material_chunks_material_id_order_index` ON `material_chunks`
            (`material_id` ASC, `order_index` ASC)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `assignment_submissions_audit` (`id` TEXT NOT NULL, `assignment_id` TEXT NOT NULL, `student_id`
            TEXT NOT NULL, `file_path` TEXT NOT NULL, `file_type` TEXT NOT NULL DEFAULT 'IMAGE', `submitted_at` INTEGER NOT
            NULL, `score` INTEGER, `teacher_feedback` TEXT, `updated_at` INTEGER NOT NULL, `sync_status` TEXT NOT NULL
            DEFAULT 'QUEUED_FOR_SYNC', `sync_reject_reason` TEXT, `received_at` INTEGER, `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`), FOREIGN KEY(`assignment_id`) REFERENCES `assignments`(`id`) ON UPDATE NO ACTION ON DELETE
            CASCADE )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `assignment_submissions_audit` (`id`, `assignment_id`, `student_id`, `file_path`, `file_type`,
            `submitted_at`, `score`, `teacher_feedback`, `updated_at`, `sync_status`, `sync_reject_reason`, `received_at`,
            `created_at`) SELECT `id`, `assignment_id`, `student_id`, `file_path`, `file_type`, `submitted_at`, `score`,
            `teacher_feedback`, `updated_at`, `sync_status`, `sync_reject_reason`, `received_at`, `submitted_at` FROM
            `assignment_submissions`
        """.trimIndent())
        db.execSQL("DROP TABLE `assignment_submissions`")
        db.execSQL("ALTER TABLE `assignment_submissions_audit` RENAME TO `assignment_submissions`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_client_submissions_sync` ON `assignment_submissions` (`sync_status` ASC, `submitted_at` DESC)")
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_assignment_submissions_assignment_id_student_id` ON
            `assignment_submissions` (`assignment_id` ASC, `student_id` ASC)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `quiz_attempts_audit` (`id` TEXT NOT NULL, `quiz_id` TEXT NOT NULL, `student_id` TEXT NOT NULL,
            `status` TEXT NOT NULL DEFAULT 'IN_PROGRESS', `started_at` INTEGER NOT NULL, `submitted_at` INTEGER, `score`
            INTEGER, `total_points` INTEGER, `answers_json` TEXT NOT NULL DEFAULT '[]', `updated_at` INTEGER NOT NULL,
            `sync_status` TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC', `sync_reject_reason` TEXT, `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`), FOREIGN KEY(`quiz_id`) REFERENCES `quizzes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `quiz_attempts_audit` (`id`, `quiz_id`, `student_id`, `status`, `started_at`, `submitted_at`,
            `score`, `total_points`, `answers_json`, `updated_at`, `sync_status`, `sync_reject_reason`, `created_at`) SELECT
            `id`, `quiz_id`, `student_id`, `status`, `started_at`, `submitted_at`, `score`, `total_points`, `answers_json`,
            `updated_at`, `sync_status`, `sync_reject_reason`, `started_at` FROM `quiz_attempts`
        """.trimIndent())
        db.execSQL("DROP TABLE `quiz_attempts`")
        db.execSQL("ALTER TABLE `quiz_attempts_audit` RENAME TO `quiz_attempts`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_client_attempts_sync` ON `quiz_attempts` (`sync_status` ASC, `submitted_at` DESC)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quiz_attempts_quiz_id_student_id` ON `quiz_attempts` (`quiz_id` ASC, `student_id` ASC)")

    }
}
