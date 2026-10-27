package cn.local.imagepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.core.PageSize
import cn.local.imagepdf.core.Quality
import cn.local.imagepdf.ui.AppUiState
import cn.local.imagepdf.ui.SortOrder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real application coordinator/service against app-owned content URIs. This does not
 * automate Photo Picker, the SAF save dialog, or third-party document-provider failure behavior.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as ImagePdfApplication).runtime

    @Test fun importArrangeGenerateRecreateExportAndRetainResult(): Unit = runBlocking {
        withTimeout(10_000) { runtime.state.first { !it.importing } }
        awaitEditable()
        val initial = runtime.state.value
        // Never replace a user's existing draft or most recent generated result.
        assumeTrue("Requires an empty app draft; existing user work is preserved",
            initial.images.isEmpty() && initial.result == null && initial.task == null)

        val token = UUID.randomUUID().toString()
        val inputs = File(context.filesDir, "results/app-flow-input-$token").apply { mkdirs() }
        val exported = File(context.cacheDir, "app-flow-export-$token.pdf")
        val sourceUris = listOf("10.jpg", "2.jpg", "1.jpg").mapIndexed { index, name ->
            val file = File(inputs, name)
            val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                    drawText(name, 20f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE; textSize = 36f
                    })
                }
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            // FileProvider supplies DISPLAY_NAME, unlike file://, and exercises actual Content URI IO.
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val ownedOrigins = sourceUris.map(Uri::toString).toSet()
        val copiedSources = mutableSetOf<File>()
        var generated: File? = null
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            onMain { runtime.dismissNotice(); runtime.importSelection(sourceUris) }
            val firstImport = awaitState { !it.importing && it.images.size == 3 }
            assertTrue(firstImport.images.all { it.error == null })
            assertEquals(listOf("10.jpg", "2.jpg", "1.jpg"), firstImport.images.map { it.name })
            assertTrue(firstImport.images.all { it.originalUri in ownedOrigins })

            onMain { runtime.importSelection(listOf(sourceUris.first())) }
            val duplicated = awaitState { !it.importing && it.images.size == 4 }
            assertTrue(duplicated.notice.orEmpty().contains("重复图片"))
            assertEquals(4, duplicated.images.map { it.id }.toSet().size)
            assertEquals(2, duplicated.images.count { it.originalUri == sourceUris.first().toString() })
            copiedSources += duplicated.images.mapNotNull { item ->
                Uri.parse(item.uri).takeIf { it.scheme == "file" }?.path?.let(::File)
            }

            onMain {
                runtime.delete(duplicated.images.last().id)
                runtime.sort(SortOrder.NAME)
            }
            val ordered = runtime.state.value.images
            assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), ordered.map { it.name })
            val settings = OutputSettings(fileName = "流程验证_$token.pdf", pageSize = PageSize.A5,
                marginMm = 5, quality = Quality.SMALL, pageNumbers = true)
            onMain {
                runtime.rotate(ordered.first().id)
                runtime.updateSettings(settings)
                runtime.dismissNotice()
                runtime.generate()
                // Once the snapshot is frozen, repeat start/config/order edits must not affect it.
                runtime.generate()
                runtime.rotate(ordered.first().id)
                runtime.updateSettings(settings.copy(pageNumbers = false))
                runtime.move(ordered.first().id, ordered.last().id)
            }
            val completed = awaitState { it.task == null && it.result?.pageCount == 3 }
            awaitEditable()
            assertEquals(settings, completed.settings)
            assertEquals(ordered.map { it.id }, completed.images.map { it.id })
            assertEquals(90, completed.images.first().rotation)
            val result = completed.result!!
            generated = File(result.filePath)
            assertTrue(generated!!.isFile)
            assertEquals(generated!!.length(), result.bytes)
            assertFalse(result.exported)
            ParcelFileDescriptor.open(generated, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(3, renderer.pageCount)
                    renderer.openPage(0).use { assertTrue("Rotated first page must be portrait", it.width < it.height) }
                    renderer.openPage(1).use { assertTrue("Second page must be landscape", it.width > it.height) }
                }
            }

            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertEquals(result, runtime.state.value.result)
            assertEquals(completed.images, runtime.state.value.images)
            assertEquals(settings, runtime.state.value.settings)

            onMain { runtime.export(null) }
            assertEquals(result, runtime.state.value.result)
            assertTrue(runtime.state.value.notice.orEmpty().contains("已取消另存"))
            assertTrue(generated!!.isFile)

            assertTrue(exported.createNewFile())
            onMain { runtime.dismissNotice(); runtime.export(Uri.fromFile(exported)) }
            val saved = awaitState { it.task == null && it.result?.exported == true }
            awaitEditable()
            assertEquals(result.filePath, saved.result!!.filePath)
            assertEquals(generated!!.length(), exported.length())
            assertArrayEquals(sha256(generated!!), sha256(exported))

            onMain { runtime.newTask() }
            assertTrue(runtime.state.value.images.isEmpty())
            assertNull(runtime.state.value.result)
            assertTrue("Starting a new task must retain the completed private PDF", generated!!.isFile)
        } finally {
            // Only reset state containing this test's selected inputs; never clear unrelated work.
            val ours = runtime.state.value.images.all { it.originalUri in ownedOrigins } &&
                (runtime.state.value.result == null || runtime.state.value.result?.filePath == generated?.path)
            if (ours) {
                if (runtime.state.value.task != null) onMain { runtime.cancel() }
                val idle = runCatching { awaitEditable() }.isSuccess
                if (idle) {
                    copiedSources += runtime.state.value.images.mapNotNull { item ->
                        Uri.parse(item.uri).takeIf { it.scheme == "file" }?.path?.let(::File)
                    }
                    onMain { runtime.newTask() }
                    // Give the atomic draft writer a chance to persist the reset before teardown.
                    delay(300)
                    copiedSources.filter { it.parentFile?.canonicalFile == File(context.filesDir, "sources").canonicalFile }
                        .forEach { it.delete() }
                    generated?.parentFile?.takeIf {
                        it.parentFile?.canonicalFile == File(context.filesDir, "results").canonicalFile
                    }?.deleteRecursively()
                    inputs.deleteRecursively()
                }
            }
            exported.delete()
            scenario?.close()
        }
    }

    private suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }

    private suspend fun awaitEditable(): Unit = withTimeout(30_000) {
        while (!withContext(Dispatchers.Main) { runtime.canPick() }) delay(20)
    }

    private suspend fun awaitState(predicate: (AppUiState) -> Boolean): AppUiState = withTimeout(30_000) {
        runtime.state.first { state ->
            assertNull("Unexpected application failure: ${state.error}", state.error)
            predicate(state)
        }
    }

    private fun sha256(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }
}
