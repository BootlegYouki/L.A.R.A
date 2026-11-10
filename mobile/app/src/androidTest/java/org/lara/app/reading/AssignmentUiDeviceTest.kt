package org.lara.app.reading

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lara.app.LaraApp
import org.lara.app.MainActivity
import org.lara.app.data.remote.discovery.HubEndpoint
import org.lara.app.data.repository.SignOutResult
import org.lara.app.ui.components.*
import org.lara.app.ui.theme.*

@RunWith(AndroidJUnit4::class)
class AssignmentUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("assignmentPhase") ?: "after"
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun waitFor(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return
            SystemClock.sleep(100)
        }
        error("Class control missing: $label")
    }

    private fun click(label: String) {
        scrollTo(label)
        var node = nodes().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun scrollTo(label: String) {
        repeat(8) {
            if (nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        waitFor(label)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (InstrumentationRegistry.getArguments().getString("homeLandscape") == "true") {
                assertTrue("Landscape evidence must be wider than tall", bitmap.width > bitmap.height)
            }
            File(context.filesDir, "assignment266-$phase-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun assignmentStatesAndPhotoFlow(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assignmentUi") == "true")
        check(context.packageName == "org.lara.app.group5")
        val owner = "assignment-fixture-${System.nanoTime()}"
        val hub = requireNotNull(HubEndpoint.lan("127.0.0.1", 9))
        val container = org.lara.app.AppContainer(context)
        container.endpoints.save(hub)
        val initialDb = org.lara.app.data.local.AccountDatabases(context).prepare(owner)
        initialDb.authDao().saveProfile(org.lara.app.data.local.entities.UserEntity(owner, "SYNTHETIC", "Sample Learner", "STUDENT", 1, 1))
        org.lara.app.data.remote.TokenStore(context).save(org.lara.app.data.remote.StoredToken("synthetic", owner, Long.MAX_VALUE, hub.restBase))
        requireNotNull(container.auth.cachedUser())
        val active = container.forUser(owner)
        // Mutate the repository's Room instance so its observed flows receive fixture changes.
        val dbField = active.homework.javaClass.getDeclaredField("db").apply { isAccessible = true }
        val db = dbField.get(active.homework) as org.lara.app.data.local.LaraDatabase
        val assignment = org.lara.app.domain.model.Assignment("leaf", "assignment-class", "Draw and label a leaf",
            "Draw a leaf from a plant near your home. Label the parts you learned about in class.",
            1893455940000L, false, 50, null, 1, 1)
        db.cacheDao().upsertBatch(org.lara.app.data.local.dao.CacheBatch(
            users = listOf(org.lara.app.data.local.entities.UserEntity("teacher", null, "Sample Teacher", "TEACHER", 1, 1)),
            classrooms = listOf(org.lara.app.data.local.entities.ClassroomEntity("assignment-class", "Science 4", "Section Mabini", "K7M4QX", "teacher", createdAt = 1, updatedAt = 1)),
            enrollments = listOf(org.lara.app.data.local.entities.EnrollmentEntity("enrollment", "assignment-class", owner, "ACTIVE", 1, 1)),
            assignments = listOf(org.lara.app.data.local.entities.AssignmentEntity("leaf", "assignment-class", assignment.title, assignment.instructions, assignment.dueDate, maxPoints = 50, createdAt = 1, updatedAt = 1)),
            syncState = listOf(org.lara.app.data.local.entities.SyncStateEntity("hub_id", "fixture-hub", 1))))
        val photo = File(active.homeworkPhotos, "fixture.jpg").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 36f }
            drawText("SYNTHETIC NOTEBOOK PAGE", 30f, 80f, paint)
            drawText("Leaf observation", 30f, 150f, paint)
            drawOval(180f, 250f, 530f, 700f, paint)
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }; bitmap.recycle()
        var screenOwner: androidx.lifecycle.ViewModelStoreOwner? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { LaraTheme {
                // The shell owns the shared class header, offline banner and bottom tabs.
                org.lara.app.ui.navigation.StudentNavGraph(org.lara.app.ui.navigation.ShellState(org.lara.app.ui.navigation.ShellRole.STUDENT), content = {
                    screenOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
                    org.lara.app.ui.screens.homework.HomeworkScreen(active, owner, assignment) {}
                })
            } } }
            scrollTo("Your work"); capture("assignment-empty")
            if (phase != "before") {
                click("Your work"); scrollTo("Add work"); capture("work-empty")
                var add = nodes().first { it.text?.toString() == "Add work" }
                while (!add.isClickable && add.parent != null) add = add.parent
                val bounds = android.graphics.Rect().also(add::getBoundsInScreen)
                assertTrue("Primary target at least 56dp", bounds.height() >= 56 * context.resources.displayMetrics.density)
                click("Add work"); waitFor("Choose a photo"); capture("add-work")
                assertFalse(nodes().any { it.text?.toString()?.contains("Drive") == true })
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor("Add work")
            }
            for ((status, reason) in listOf("QUEUED_FOR_SYNC" to null, "SYNCED" to null, "REJECTED" to "LATE_SUBMISSION", "REJECTED" to "ALREADY_GRADED")) {
                db.homeworkDao().save(org.lara.app.data.local.entities.AssignmentSubmissionEntity("submission", "leaf", owner, photo.path,
                    submittedAt = 1, updatedAt = System.currentTimeMillis(), syncStatus = status, syncRejectReason = reason))
                waitFor(context.getString(when (status) {
                    "QUEUED_FOR_SYNC" -> org.lara.app.R.string.homework_queued
                    "SYNCED" -> org.lara.app.R.string.homework_turned_in
                    else -> org.lara.app.R.string.homework_rejected
                }))
                if (phase != "before" && reason != null) scrollTo(context.getString(if (reason == "LATE_SUBMISSION") org.lara.app.R.string.homework_rejected_late else org.lara.app.R.string.homework_rejected_graded))
                capture("state-${reason ?: status}")
            }
            if (phase != "before") {
                click("Add work"); click("Take a new photo")
            } else click("Take a new photo")
            waitFor("Photograph your work"); capture("camera")
            click("Take a photo")
            scrollTo("Use this photo"); capture("scan-preview")
            click("Use this photo"); waitFor(context.getString(org.lara.app.R.string.homework_photo_ready))
            if (phase != "before") { click("Your work"); scrollTo("Turn in") }
            capture("photo-ready")
            click("Turn in")
            if (phase != "before") {
                waitFor("Turn in your work?"); capture("turn-in-confirm")
                click("Cancel")
                assertEquals("REJECTED", db.homeworkDao().find("leaf", owner)?.syncStatus)
                click("Turn in"); click("Turn in")
            }
            waitFor(context.getString(org.lara.app.R.string.homework_queued))
            val row = requireNotNull(db.homeworkDao().find("leaf", owner))
            assertEquals("QUEUED_FOR_SYNC", row.syncStatus)
            assertTrue(File(row.filePath).length() in 1 until 800_000)
            capture("turned-in-offline")
            if (phase != "before") {
                click("Add work"); click("Choose a photo")
                SystemClock.sleep(800)
                assertFalse("System photo selector opens", nodes().any { it.text?.toString() == "Choose a photo" })
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                scenario.onActivity { activity ->
                    val model = androidx.lifecycle.ViewModelProvider(requireNotNull(screenOwner))["homework-$owner-leaf", org.lara.app.ui.screens.homework.HomeworkViewModel::class.java]
                    model.choose(context, android.net.Uri.fromFile(File(row.filePath)))
                }
                scrollTo("Use this photo"); capture("import-preview")
                click("Use this photo"); waitFor(context.getString(org.lara.app.R.string.homework_photo_ready))
                capture("import-ready")
                assertEquals("Draft replacement does not overwrite queued work", row, db.homeworkDao().find("leaf", owner))
            }
        }
    }

    @Test fun liveMockAssignmentTurnIn(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assignmentHub") == "true")
        check(context.packageName == "org.lara.app.group5")
        org.lara.app.data.remote.TokenStore(context).clear()
        val container = (context.applicationContext as LaraApp).container
        val endpoint = requireNotNull(HubEndpoint.manual("127.0.0.1:8080"))
        container.endpoints.save(endpoint, "Synthetic Group 5 mock Hub")
        val user = container.auth.authenticate(endpoint, "123456789012", "1234", null)
        val active = container.forUser(user.id)
        active.sync.pull(user.id)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job())
        try {
            val classes = active.classrooms.classrooms(scope).first { it is org.lara.app.domain.Cached.Content }
            val classroom = (classes as org.lara.app.domain.Cached.Content).value.first { it.name == "Science 4" }
            val cached = active.classwork.classwork(classroom.id, scope).first { it is org.lara.app.domain.Cached.Content }
            val assignment = (cached as org.lara.app.domain.Cached.Content).value.assignments.first()
            var screenOwner: androidx.lifecycle.ViewModelStoreOwner? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { LaraTheme {
                    org.lara.app.ui.navigation.StudentNavGraph(org.lara.app.ui.navigation.ShellState(org.lara.app.ui.navigation.ShellRole.STUDENT, isHubReachable = true), content = {
                        screenOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
                        org.lara.app.ui.screens.homework.HomeworkScreen(active, user.id, assignment) {}
                    })
                } } }
                waitFor("Your work"); capture("live-mock-assignment")
                val source = File(context.cacheDir, "synthetic-gallery.jpg")
                val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }; bitmap.recycle()
                scenario.onActivity {
                    androidx.lifecycle.ViewModelProvider(requireNotNull(screenOwner))["homework-${user.id}-${assignment.id}", org.lara.app.ui.screens.homework.HomeworkViewModel::class.java]
                        .choose(context, android.net.Uri.fromFile(source))
                }
                scrollTo("Use this photo"); click("Use this photo")
                waitFor(context.getString(org.lara.app.R.string.homework_photo_ready))
                click("Turn in"); waitFor("Turn in your work?"); click("Turn in")
                kotlinx.coroutines.withTimeout(10_000) {
                    active.homework.observe(assignment.id).first { it != null && it.syncStatus != "REJECTED" }
                }
                active.homework.uploadQueued()
                waitFor(context.getString(org.lara.app.R.string.homework_turned_in))
                click("Your work"); capture("live-mock-submitted")
                click("Your work"); capture("live-mock-submitted-collapsed")
                val result = active.homework.observe(assignment.id).first()
                assertEquals("SYNCED", result?.syncStatus)
                assertNotNull(result?.receivedAt)
                assertTrue(File(requireNotNull(result).filePath).isFile)
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
