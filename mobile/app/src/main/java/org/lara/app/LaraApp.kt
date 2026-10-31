package org.lara.app

import android.app.Application
import android.content.Context
import org.lara.app.data.remote.discovery.AndroidHubDiscovery
import org.lara.app.domain.discovery.HubDiscovery
import org.lara.app.data.local.LaraDatabase
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
}

class AppContainer(context: Context) {
    val endpoints = org.lara.app.data.remote.discovery.HubEndpointStore(context)
    val auth by lazy { org.lara.app.data.repository.AuthRepository(
        org.lara.app.data.remote.AuthApi(), org.lara.app.data.remote.TokenStore(context), database.authDao()) }
    val discovery: HubDiscovery = AndroidHubDiscovery(context)
    private val database by lazy { LaraDatabase.create(context.applicationContext) }
    private val sessionTokens by lazy { org.lara.app.data.remote.TokenStore(context) }
    private val hubClient by lazy { org.lara.app.data.remote.HubClient() }
    val enrollmentApi by lazy { org.lara.app.data.remote.EnrollmentApi(hubClient, sessionTokens, endpoints::load) }
    fun classManagement(userId: String): org.lara.app.data.remote.ClassManagementRepository =
        org.lara.app.data.remote.HubClassManagementRepository(userId, enrollmentApi, hubClient)
    fun savedRosterRefresher(remote: org.lara.app.data.remote.ClassManagementRepository) =
        org.lara.app.data.repository.SavedRosterRefresher(database, remote)
    val enrollmentNotices by lazy { org.lara.app.data.repository.RoomEnrollmentNoticeRepository(database) }
    val classrooms: ClassroomRepository by lazy { RoomClassroomRepository(database.classroomDao()) }
    val stream: StreamRepository by lazy { RoomStreamRepository(database.streamDao()) }
    val classwork: ClassworkRepository by lazy {
        RoomClassworkRepository(database.classworkDao(), database.quizDao())
    }
    val enrollments: EnrollmentRepository by lazy { RoomEnrollmentRepository(database.rosterDao()) }
    val quizzes: QuizRepository by lazy { RoomQuizRepository(database.quizDao()) }
}
