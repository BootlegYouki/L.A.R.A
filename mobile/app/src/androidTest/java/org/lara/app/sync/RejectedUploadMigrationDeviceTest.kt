package org.lara.app.sync
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class RejectedUploadMigrationDeviceTest : RejectedUploadMigrationTests() {
    override fun versionOneSchema() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("org.lara.app.data.local.LaraDatabase/1.json").bufferedReader().use { it.readText() }
}
