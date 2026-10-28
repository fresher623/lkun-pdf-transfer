package cn.local.imagepdf

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Sends only an obsolete cancel token; never creates, cancels or replaces a user's real task. */
@RunWith(AndroidJUnit4::class)
class ServiceNotificationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ImagePdfApplication

    @Test fun staleCancelAtIdleStopsServiceAndPreservesBothTools(): Unit = runBlocking {
        assumeTrue("Run service fixtures only in an isolated application", context.packageName != "cn.local.imagepdf")
        withTimeout(15_000) { app.runtime.state.first { !it.importing && !it.readingFolder } }
        withTimeout(15_000) { app.mergeRuntime.state.first { !it.importing } }
        assumeTrue("Do not interrupt either tool", app.runtime.state.value.task == null && app.mergeRuntime.state.value.task == null)
        // Let startup-only cache cleanup relinquish its lease, without touching its work.
        withTimeout(5_000) {
            while (app.taskGate.owner != null) {
                assumeTrue("A user task started while preparing the test", app.runtime.state.value.task == null && app.mergeRuntime.state.value.task == null)
                delay(25)
            }
        }
        assumeTrue("An unrelated service instance must not be stopped", !ownServiceRunning())
        val imageBefore = app.runtime.state.value
        val mergeBefore = app.mergeRuntime.state.value
        var scenario: ActivityScenario<MainActivity>? = null
        var sent = false
        try {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, MainActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario = ActivityScenario.launch<MainActivity>(launch)
            assumeTrue("Do not interrupt work started after activity launch", app.runtime.state.value.task == null &&
                app.mergeRuntime.state.value.task == null && !app.runtime.state.value.importing &&
                !app.mergeRuntime.state.value.importing && app.taskGate.owner == null)
            val staleId = "stale-${UUID.randomUUID()}"
            val cancel = Intent(context, ConversionService::class.java)
                .setAction("cn.local.imagepdf.CANCEL")
                .putExtra(ConversionService.EXTRA_TOOL, ConversionService.TOOL_MERGE)
                .putExtra(ConversionService.EXTRA_TASK_ID, staleId)
                .setData(Uri.parse("lkun-pdf://cancel/merge/$staleId"))
            onMain { ContextCompat.startForegroundService(context, cancel); sent = true }
            // Main-thread barrier plus a stable absence interval avoids passing simply because
            // the asynchronous service start had not yet reached ApplicationThread.
            onMain { Unit }
            withTimeout(5_000) {
                var absentSince = 0L
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (ownServiceRunning()) absentSince = 0L
                    else if (absentSince == 0L) absentSince = now
                    else if (now - absentSince >= 600) break
                    delay(50)
                }
            }
            assertNull("An obsolete notification must not acquire a task lease", app.taskGate.owner)
            assertNull(app.runtime.state.value.task)
            assertNull(app.mergeRuntime.state.value.task)
        } finally {
            // On regression, remove only the idle service that this test created. Never stop a
            // newly started real task, and never clear either runtime to make assertions pass.
            if (sent && app.taskGate.owner == null && app.runtime.state.value.task == null &&
                app.mergeRuntime.state.value.task == null && ownServiceRunning()) {
                context.stopService(Intent(context, ConversionService::class.java))
            }
            scenario?.close()
            // Boolean assertions do not print private filenames, selected URIs or result paths.
            assertTrue("Image draft must remain unchanged", imageBefore.images == app.runtime.state.value.images &&
                imageBefore.settings == app.runtime.state.value.settings)
            assertTrue("Image result must remain unchanged", imageBefore.result == app.runtime.state.value.result)
            assertTrue("Merge draft must remain unchanged", mergeBefore.entries == app.mergeRuntime.state.value.entries &&
                mergeBefore.outputName == app.mergeRuntime.state.value.outputName)
            assertTrue("Merge result must remain unchanged", mergeBefore.result == app.mergeRuntime.state.value.result)
        }
    }

    @Suppress("DEPRECATION")
    private fun ownServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE).any { running ->
            running.service.packageName == context.packageName && running.service.className == ConversionService::class.java.name
        }
    }

    private suspend fun onMain(action: () -> Unit): Unit = withContext(Dispatchers.Main) { action() }
}
