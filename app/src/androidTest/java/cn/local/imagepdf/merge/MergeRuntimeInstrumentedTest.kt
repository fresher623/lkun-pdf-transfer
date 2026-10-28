package cn.local.imagepdf.merge

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.ImagePdfApplication
import cn.local.imagepdf.MainActivity
import cn.local.imagepdf.core.LayoutCalculator
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.core.StreamingPdfWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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

/** Real coordinator/service integration. Uses no system selector and no user's existing draft. */
@RunWith(AndroidJUnit4::class)
class MergeRuntimeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ImagePdfApplication
    private val runtime get() = app.mergeRuntime

    @Test fun importMergeRecreateAndExportPreservesImageWork(): Unit = runBlocking {
        assumeTrue("Run only in an isolated test application", context.packageName != "cn.local.imagepdf")
        val images = withTimeout(15_000) { app.runtime.state.first { !it.importing && !it.readingFolder } }
        val initial = withTimeout(15_000) { runtime.state.first { !it.importing } }
        assumeTrue("Do not interrupt an image task", images.task == null)
        assumeTrue("Preserve any existing PDF merge draft", initial.entries.isEmpty() && initial.result == null &&
            initial.task == null && initial.duplicateCount == 0 && initial.outputName == MergeState().outputName)
        awaitEditable()
        val token = UUID.randomUUID().toString()
        val inputs = File(context.filesDir, "results/merge-runtime-input-$token").apply { mkdirs() }
        val first = File(inputs, "first.pdf")
        val second = File(inputs, "second.pdf")
        val outputName = "runtime-$token.pdf"
        val exported = File(context.cacheDir, "merge-runtime-export-$token.pdf")
        val cached = mutableSetOf<File>()
        var generated: File? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val origins = mutableSetOf<String>()
        try {
            createPdf(first, 1, inputs)
            createPdf(second, 2, inputs)
            val uris = listOf(first, second).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
            origins += uris.map(Uri::toString)
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, MainActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario = ActivityScenario.launch<MainActivity>(launch)
            stage("importing controlled PDFs through MergeRuntime")
            onMain { runtime.dismissNotice(); runtime.importSelection(uris) }
            val imported = awaitState { !it.importing && it.entries.size == 2 }
            cached += imported.entries.filter { it.sourcePath.isNotBlank() }.map { File(it.sourcePath) }
            assertTrue("Controlled PDFs must be valid", imported.entries.all { it.issue == null })
            assertEquals(3, imported.totalPages)
            onMain { assertTrue("Import completion must immediately permit editing", runtime.canPick()) }
            awaitEditable()

            onMain { runtime.importSelection(listOf(uris.first())) }
            assertEquals(1, runtime.state.value.duplicateCount)
            assertTrue("Duplicates require explicit confirmation", runtime.state.value.entries == imported.entries)
            onMain { runtime.confirmDuplicates(false) }
            assertEquals(2, runtime.state.value.entries.size)
            onMain { runtime.importSelection(listOf(uris.first())); runtime.confirmDuplicates(true) }
            val duplicate = awaitState { !it.importing && it.entries.size == 3 }
            cached += duplicate.entries.filter { it.sourcePath.isNotBlank() }.map { File(it.sourcePath) }
            assertEquals(3, duplicate.entries.map { it.id }.toSet().size)
            awaitEditable()
            onMain { runtime.remove(duplicate.entries.last().id) }
            awaitEditable()
            onMain {
                runtime.move(imported.entries.first().id, imported.entries.last().id)
                runtime.setOutputName(outputName)
                runtime.generate()
                val competing = app.taskGate.tryAcquire("test-image-competition")
                competing?.close()
                assertNull("Other tools must not acquire the active merge lease", competing)
                runtime.generate()
                runtime.setOutputName("blocked.pdf")
                runtime.move(imported.entries.last().id, imported.entries.first().id)
            }
            val completed = awaitState { it.task == null && it.result?.pageCount == 3 }
            onMain { assertTrue("A completed merge must immediately permit the next action", runtime.canPick()) }
            awaitEditable()
            assertEquals(outputName, completed.outputName)
            assertEquals(listOf(imported.entries.last().id, imported.entries.first().id), completed.entries.map { it.id })
            generated = File(completed.result!!.filePath)
            assertTrue(generated!!.isFile)
            ParcelFileDescriptor.open(generated, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { assertEquals(3, it.pageCount) }
            }
            PDDocument.load(generated).use { document ->
                val labels = PDFTextStripper().getText(document).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                assertEquals("Text must follow reordered source pages", listOf("1", "2", "1"), labels)
            }
            scenario.recreate()
            assertTrue("Recreation must preserve the merge draft", completed.entries == runtime.state.value.entries)
            assertTrue("Recreation must preserve the result", completed.result == runtime.state.value.result)
            onMain {
                assertEquals(completed.result, runtime.prepareExportSelection())
                assertFalse("Save picker must freeze the current result", runtime.canPick())
                val competing = app.taskGate.tryAcquire("test-image-while-saving")
                competing?.close()
                assertNull("The save picker owns the shared lease", competing)
                runtime.newTask()
                runtime.setOutputName("must-not-replace-save-result.pdf")
                assertEquals(completed.result, runtime.state.value.result)
                assertEquals(outputName, runtime.state.value.outputName)
            }
            scenario.recreate()
            onMain {
                assertEquals("Recreation must preserve the save selection lease", "merge", app.taskGate.owner)
                assertFalse(runtime.canPick())
                runtime.export(null)
                assertNull("Cancelling save selection releases its lease", app.taskGate.owner)
                assertTrue(runtime.canPick())
                assertEquals(completed.result, runtime.prepareExportSelection())
                runtime.cancelExportSelection()
                assertNull("Save picker launch failure releases its lease", app.taskGate.owner)
                assertTrue(runtime.canPick())
            }
            assertTrue(runtime.state.value.notice.orEmpty().contains("已取消另存"))
            assertTrue(generated!!.isFile)
            assertTrue(exported.createNewFile())
            onMain { runtime.dismissNotice(); runtime.export(Uri.fromFile(exported)) }
            awaitState { it.task == null && it.result?.exported == true }
            onMain { assertTrue("A completed export must immediately permit the next action", runtime.canPick()) }
            awaitEditable()
            assertArrayEquals(sha256(generated!!), sha256(exported))
            onMain { runtime.newTask() }
            assertTrue(runtime.state.value.entries.isEmpty())
            assertTrue("New task must retain the complete private PDF", generated!!.isFile)
            stage("merge service, recreation, cancellation of save picker and export hash checks completed")
        } finally {
            val ours = runtime.state.value.entries.all { it.originalUri in origins } &&
                (runtime.state.value.result == null || runtime.state.value.result?.filePath == generated?.path)
            if (ours) {
                onMain { runtime.cancelExportSelection() }
                if (runtime.state.value.task != null) onMain { runtime.cancel() }
                if (runCatching { awaitEditable() }.isSuccess) {
                    cached += runtime.state.value.entries.filter { it.sourcePath.isNotBlank() }.map { File(it.sourcePath) }
                    onMain { runtime.newTask() }
                    delay(300)
                    cached.filter { it.parentFile?.canonicalFile == File(context.filesDir, "merge-sources").canonicalFile }.forEach { it.delete() }
                    generated?.parentFile?.takeIf { it.parentFile?.canonicalFile == File(context.filesDir, "results").canonicalFile }
                        ?.deleteRecursively()
                    inputs.deleteRecursively()
                }
            }
            exported.delete()
            scenario?.close()
            assertTrue("Image draft must remain unchanged", images.images == app.runtime.state.value.images)
            assertTrue("Image result must remain unchanged", images.result == app.runtime.state.value.result)
        }
    }

    private fun createPdf(target: File, pages: Int, directory: File) {
        val image = File(directory, "fixture.jpg")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { bitmap.recycle() }
        val settings = OutputSettings(pageNumbers = true)
        StreamingPdfWriter(target, pages).use { writer ->
            repeat(pages) { index -> writer.addPage(image, 120, 80, LayoutCalculator.calculate(120, 80, settings), index + 1) }
            writer.finish()
        }
    }
    private suspend fun awaitEditable(): Unit = withTimeout(45_000) {
        while (!withContext(Dispatchers.Main) { runtime.canPick() }) delay(25)
    }
    private suspend fun awaitState(predicate: (MergeState) -> Boolean): MergeState = withTimeout(45_000) {
        runtime.state.first { assertNull("Unexpected merge error", it.error); predicate(it) }
    }
    private suspend fun onMain(action: () -> Unit): Unit = withContext(Dispatchers.Main) { action() }
    private fun stage(text: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Merge runtime: $text\n") })
    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest()
    }
}
