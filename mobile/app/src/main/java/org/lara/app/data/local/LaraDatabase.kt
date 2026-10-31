package org.lara.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.lara.app.data.local.dao.*
import org.lara.app.data.local.entities.*

@Database(
    entities = [
        UserEntity::class,
        ClassroomEntity::class,
        EnrollmentEntity::class,
        AnnouncementEntity::class,
        AnnouncementCommentEntity::class,
        MaterialEntity::class,
        MaterialChunkEntity::class,
        AssignmentEntity::class,
        AssignmentSubmissionEntity::class,
        QuizEntity::class,
        QuizQuestionEntity::class,
        QuizAttemptEntity::class,
        AiChatMessageEntity::class,
        TopicEntity::class,
        PrivateCommentEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true,
)
abstract class LaraDatabase : RoomDatabase() {
    abstract fun authDao(): AuthDao
    abstract fun classroomDao(): ClassroomDao
    abstract fun streamDao(): StreamDao
    abstract fun classworkDao(): ClassworkDao
    abstract fun rosterDao(): RosterDao
    abstract fun quizDao(): QuizDao
    abstract fun cacheDao(): CacheDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncDao(): SyncDao

    companion object {
        fun create(context: Context): LaraDatabase = Room.databaseBuilder(
            context.applicationContext, LaraDatabase::class.java, "lara.db",
        ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
