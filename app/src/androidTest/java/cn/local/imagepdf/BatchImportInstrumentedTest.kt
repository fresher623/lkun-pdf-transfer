package cn.local.imagepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.ui.AppUiState
import java.io.File
import java.util.Locale
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

/** Exercises one 500-URI selection response, independently of system picker UI restrictions. */
@RunWith(AndroidJUnit4::class)
class BatchImportInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = (context.applicationContext as ImagePdfApplication).runtime

    @Test fun singleBatch500AndExplicit501Confirmation(): Unit = runBlocking {
        assumeTrue("Run on the isolated benchmark app only", context.packageName.endsWith(".benchmark"))
        withTimeout(10_000) { runtime.state.first { !it.importing } }
        awaitEditable()
        val initial = runtime.state.value
        assumeTrue("Existing draft/result is preserved", initial.images.isEmpty() &&
            initial.result == null && initial.task == null && initial.pendingImportCount == 0)

        val token = UUID.randomUUID().toString()
        val inputs = File(context.filesDir, "results/batch-import-input-$token").apply { mkdirs() }
        val ownedSourceCopies = mutableSetOf<File>()
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
        val uris: List<Uri>
        try {
            uris = (1..501).map { number ->
                val name = String.format(Locale.ROOT, "图片_%04d.jpg", number)
                val source = File(inputs, name)
                canvas.drawColor(Color.rgb(25 + number % 180, 60, 120))
                canvas.drawText(number.toString(), 24f, 110f, paint)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                FileProvider.getUriForFile(context, "${context.packageName}.files", source)
            }
        } finally { bitmap.recycle() }
        val ownedOrigins = uris.map(Uri::toString).toSet()

        fun rememberSourceCopies(state: AppUiState) {
            ownedSourceCopies += state.images.filter { it.originalUri in ownedOrigins }.mapNotNull { item ->
                Uri.parse(item.uri).takeIf { it.scheme == "file" }?.path?.let(::File)
            }
        }

        try {
            onMain { runtime.dismissError(); runtime.dismissNotice(); runtime.importSelection(uris.take(500)) }
            val first = awaitState { !it.importing && it.images.size == 500 }
            rememberSourceCopies(first)
            assertEquals(500, first.importDone)
            assertEquals(500, first.importTotal)
            assertTrue("All 500 small JPEGs must import without errors", first.images.all { it.error == null })
            assertEquals(500, first.images.map { it.id }.toSet().size)
            assertEquals(uris.take(500).map(Uri::toString), first.images.map { it.originalUri })
            assertEquals((0L until 500L).toList(), first.images.map { it.originalOrder })
            val finalId = first.images.last().id
            onMain { runtime.rotate(finalId) }
            assertEquals(finalId, runtime.state.value.images.last().id)
            assertEquals(90, runtime.state.value.images.last().rotation)
            assertEquals(first.images.map { it.id }, runtime.state.value.images.map { it.id })

            onMain { runtime.newTask(); runtime.importSelection(uris) }
            val pending = runtime.state.value
            assertEquals(501, pending.pendingImportCount)
            assertTrue("501 must wait for confirmation without silently importing", pending.images.isEmpty())
            assertFalse(pending.importing)
            onMain { runtime.confirmImport(false) }
            assertEquals(0, runtime.state.value.pendingImportCount)
            assertTrue(runtime.state.value.images.isEmpty())
            assertFalse(runtime.state.value.importing)

            onMain { runtime.importSelection(uris) }
            assertEquals(501, runtime.state.value.pendingImportCount)
            assertTrue(runtime.state.value.images.isEmpty())
            onMain { runtime.confirmImport(true) }
            val accepted = awaitState { !it.importing && it.images.size == 500 }
            rememberSourceCopies(accepted)
            assertEquals(0, accepted.pendingImportCount)
            assertEquals(500, accepted.importDone)
            assertEquals(500, accepted.images.map { it.id }.toSet().size)
            assertTrue(accepted.images.all { it.error == null })
            assertEquals(uris.take(500).map(Uri::toString), accepted.images.map { it.originalUri })
            assertEquals((0L until 500L).toList(), accepted.images.map { it.originalOrder })
            assertFalse("The unconfirmed extra entry must not appear", accepted.images.any {
                it.originalUri == uris.last().toString()
            })
        } finally {
            val idle = runCatching { awaitEditable() }.isSuccess
            val ours = runtime.state.value.images.all { it.originalUri in ownedOrigins } &&
                runtime.state.value.result == null && runtime.state.value.task == null
            if (idle && ours) {
                rememberSourceCopies(runtime.state.value)
                onMain { runtime.confirmImport(false); runtime.newTask() }
                delay(300)
                ownedSourceCopies.filter {
                    it.parentFile?.canonicalFile == File(context.filesDir, "sources").canonicalFile
                }.forEach { it.delete() }
                inputs.deleteRecursively()
            }
        }
    }

    private suspend fun onMain(action: () -> Unit): Unit = withContext(Dispatchers.Main) { action() }

    private suspend fun awaitEditable(): Unit = withTimeout(120_000) {
        while (!withContext(Dispatchers.Main) { runtime.canPick() }) delay(25)
    }

    private suspend fun awaitState(predicate: (AppUiState) -> Boolean): AppUiState = withTimeout(120_000) {
        runtime.state.first { state ->
            assertNull("Unexpected application error: ${state.error}", state.error)
            predicate(state)
        }
    }
}
