package org.lara.app.sync
import android.app.Application
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RejectedUploadMigrationTest : RejectedUploadMigrationTests() {
    override fun versionOneSchema() = File(System.getProperty("lara.schemaDir"), "org.lara.app.data.local.LaraDatabase/1.json").readText()
}
