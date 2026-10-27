package cn.local.imagepdf.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.core.ImageEntry
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.core.Quality
import cn.local.imagepdf.ImagePdfApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun awaitRuntimeCleanup(): Unit = runBlocking {
        // Startup removes leftovers from dead tasks; do not let that cleanup race a test conversion.
        val runtime = (context.applicationContext as ImagePdfApplication).runtime
        withTimeout(10_000) { runtime.state.first { !it.importing } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun exifEightDirectionsAndUserRotations(): Unit = runBlocking {
        val folder = File(context.cacheDir, "orientation-test").apply { mkdirs() }
        val file = File(folder, "corners.png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            val paint = Paint()
            paint.color = Color.RED; drawRect(0f, 0f, 60f, 40f, paint)
            paint.color = Color.GREEN; drawRect(60f, 0f, 120f, 40f, paint)
            paint.color = Color.BLUE; drawRect(0f, 40f, 60f, 80f, paint)
            paint.color = Color.YELLOW; drawRect(60f, 40f, 120f, 80f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
        )
        try {
            for (orientation in 1..8) {
                var corners = expected[orientation - 1]
                for (rotation in listOf(0, 90, 180, 270)) {
                    val item = ImageEntry("orient", Uri.fromFile(file).toString(), "方向图", 120, 80, orientation, rotation)
                    val result = BitmapPipeline(context).preview(item, 160)
                    try {
                        val actual = listOf(result.getPixel(5, 5), result.getPixel(result.width - 6, 5),
                            result.getPixel(5, result.height - 6), result.getPixel(result.width - 6, result.height - 6))
                        assertEquals("EXIF=$orientation rotation=$rotation", corners, actual)
                    } finally { result.recycle() }
                    corners = listOf(corners[2], corners[0], corners[3], corners[1])
                }
            }
        } finally { file.delete(); folder.delete() }
    }

    @Test fun transparencyWhiteAndSinglePagePdf(): Unit = runBlocking {
        val image = File(context.cacheDir, "transparent-test.png")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(50f, 30f, 110f, 70f, Paint().apply { color = Color.RED })
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val item = ImageEntry("alpha", Uri.fromFile(image).toString(), "透明图片.png", 160, 100)
        val preview = BitmapPipeline(context).preview(item, 160)
        try { assertEquals(Color.WHITE, preview.getPixel(5, 5)); assertEquals(Color.RED, preview.getPixel(80, 50)) }
        finally { preview.recycle() }
        val task = "instrument-single-${UUID.randomUUID()}"
        val progress = mutableListOf<Int>()
        val result = ImageConverter(context).convert(listOf(item), OutputSettings(fileName = "中文结果.pdf", pageNumbers = true), task) { completed, _, _ -> progress += completed }
        try {
            assertTrue(result.isFile)
            assertTrue(File(result.parentFile, "measurement.csv").readText().contains("1,"))
            ParcelFileDescriptor.open(result, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { page -> assertTrue(page.width > page.height) }
                }
            }
            assertEquals(1, progress.last())
        } finally { result.parentFile?.deleteRecursively(); image.delete() }
    }

    @Test fun cancellationRemovesPartialPdf(): Unit = runBlocking {
        val image = File(context.cacheDir, "cancel-test.jpeg")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        val task = "instrument-cancel-${UUID.randomUUID()}"
        val item = ImageEntry("cancel", Uri.fromFile(image).toString(), "取消测试.jpeg", 640, 480)
        var completed = 0
        val job = async {
            ImageConverter(context).convert(List(30) { item.copy(id = "cancel-$it") }, OutputSettings(), task) { done, _, _ ->
                completed = done
                if (done == 1) throw CancellationException("test cancel")
            }
        }
        try {
            try { job.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(1, completed)
            val folder = File(context.filesDir, "results/$task")
            assertFalse(folder.listFiles().orEmpty().any { it.extension == "pdf" || it.extension == "partial" || it.name == "page.jpeg" })
            folder.deleteRecursively()
        } finally { image.delete() }
    }

    @Test fun corruptImagesAreVisibleAndGifIsRejected(): Unit = runBlocking {
        val folder = File(context.cacheDir, "import-test").apply { mkdirs() }
        val corrupt = File(folder, "损坏.png").apply { writeText("not a PNG") }
        val gif = File(folder, "动态.gif").apply { writeBytes("GIF89a".toByteArray()) }
        val repository = ImageRepository(context)
        val items = repository.importImages(listOf(Uri.fromFile(corrupt), Uri.fromFile(gif)), 0)
        try {
            assertEquals(2, items.size)
            assertNotNull(items[0].error)
            assertTrue(items[1].error.orEmpty().contains("GIF"))
            assertNotEquals(items[0].id, items[1].id)
            assertEquals(Uri.fromFile(corrupt).toString(), items[0].originalUri)
            assertEquals(Uri.fromFile(gif).toString(), items[1].originalUri)
        } finally {
            // Do not release unrelated app drafts or persisted grants from an instrumented test.
            items.forEach { Uri.parse(it.uri).path?.let(::File)?.takeIf { f -> f.parentFile?.name == "sources" }?.delete() }
            corrupt.delete(); gif.delete(); folder.delete()
        }
    }
}
