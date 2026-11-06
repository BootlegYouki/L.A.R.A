package org.lara.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.map
import org.lara.app.data.remote.discovery.AndroidHubDiscovery
import org.lara.app.domain.discovery.HubDiscovery
import org.lara.app.data.repository.RoomClassroomRepository
import org.lara.app.data.repository.RoomClassworkRepository
import org.lara.app.data.repository.RoomEnrollmentRepository
import org.lara.app.data.repository.RoomQuizRepository
import org.lara.app.data.repository.RoomStreamRepository
import org.lara.app.domain.ClassroomRepository
import org.lara.app.domain.ClassworkRepository
import org.lara.app.domain.EnrollmentRepository
import org.lara.app.domain.QuizRepository
import org.lara.app.domain.StreamRepository

class LaraApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() { super.onCreate(); container.syncScheduler.start() }
}

private class AppServices(context: Context) {
    val handoutRoot = java.io.File(context.filesDir, "handouts")
    val accountContainers = java.util.concurrent.ConcurrentHashMap<String, AppContainer>()
    val endpoints = org.lara.app.data.remote.discovery.HubEndpointStore(context)
    val tokens = org.lara.app.data.remote.TokenStore(context)
    val accounts = org.lara.app.data.local.AccountDatabases(context)
    val discovery: HubDiscovery = AndroidHubDiscovery(context)
    val scheduler = org.lara.app.data.sync.SyncScheduler(context)
    val auth = org.lara.app.data.repository.AuthRepository(org.lara.app.data.remote.AuthApi(), tokens) {
        userId -> accounts.prepare(userId).authDao()
    }
}

class AppContainer private constructor(private val services: AppServices, private val accountId: String?) {
    constructor(context: Context) : this(AppServices(context.applicationContext), null)
    val endpoints get() = services.endpoints
    val auth get() = services.auth
    val discovery get() = services.discovery
    val syncScheduler get() = services.scheduler
    private val database get() = services.accounts.prepared(requireNotNull(accountId))
    private val sessionTokens get() = services.tokens
    private val hubClient by lazy { org.lara.app.data.remote.HubClient() }
    fun forUser(userId: String): AppContainer {
        services.accounts.prepared(userId)
        // Foreground actions and WorkManager must use the same account sync mutex.
        return services.accountContainers.computeIfAbsent(userId) { AppContainer(services, it) }
    }
    val sync by lazy { org.lara.app.data.sync.SyncRepository(database, org.lara.app.data.sync.HubSyncTransport(enrollmentApi, hubClient)) {
        materialFiles.prune()
    } }
    suspend fun syncUserId(): String? {
        val token = try { sessionTokens.load() } catch (_: Exception) { null } ?: return null
        return services.accounts.prepare(token.userId).authDao().currentUserId()?.takeIf { it == token.userId }
    }
    val settings by lazy { org.lara.app.data.repository.SettingsRepository(database.authDao(), sessionTokens, sync, endpoints::load) }
    val enrollmentApi by lazy { org.lara.app.data.remote.EnrollmentApi(hubClient, sessionTokens, endpoints::load) }
    fun classManagement(userId: String): org.lara.app.data.remote.ClassManagementRepository =
        org.lara.app.data.remote.HubClassManagementRepository(userId, enrollmentApi, hubClient)
    fun teacherClasses(userId: String) = org.lara.app.data.remote.TeacherClassApi(userId, enrollmentApi, hubClient)
    val teacherNames get() = database.authDao().observeTeacherNames()
    val commentNames by lazy { database.authDao().observeCommentNames().map { names ->
        names.map { org.lara.app.domain.CommentAuthor(it.id, it.full_name, it.role) }
    } }
    val materialFiles by lazy {
        val owner = requireNotNull(accountId)
        val folder = java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        org.lara.app.data.repository.MaterialFiles(database, enrollmentApi, java.io.File(services.handoutRoot, folder), owner)
    }
    fun savedRosterRefresher(remote: org.lara.app.data.remote.ClassManagementRepository, userId: String) =
        org.lara.app.data.repository.SavedRosterRefresher(database, remote, userId)
    val enrollmentNotices by lazy { org.lara.app.data.repository.RoomEnrollmentNoticeRepository(database) }
    val classrooms: ClassroomRepository by lazy { RoomClassroomRepository(database.classroomDao()) }
    val managedClasses: ClassroomRepository by lazy { RoomClassroomRepository(database.classroomDao(), includeArchived = true) }
    val stream: StreamRepository by lazy { RoomStreamRepository(database.streamDao(), onQueued = syncScheduler::enqueueNow) }
    val classwork: ClassworkRepository by lazy {
        RoomClassworkRepository(database.classworkDao(), database.quizDao())
    }
    val enrollments: EnrollmentRepository by lazy { RoomEnrollmentRepository(database.rosterDao(),
        onRefresh = { userId -> sync.pull(userId) }) }
    val quizzes: QuizRepository by lazy { RoomQuizRepository(database.quizDao()) }
}
