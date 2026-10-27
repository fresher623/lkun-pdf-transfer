package cn.local.imagepdf.platform

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.core.ImageEntry
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.BuildConfig
import cn.local.imagepdf.ImagePdfApplication
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: -e performance true -e sizes 100,300,500 -e repeats500 3. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BatchPerformanceTest {
    @Test fun generated12MegapixelBatches(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Add -e performance true to run synthetic 12MP batches", arguments.getString("performance") == "true")
        val context = instrumentation.targetContext
        withTimeout(10_000) { (context.applicationContext as ImagePdfApplication).runtime.state.first { !it.importing } }
        instrumentation.waitForIdleSync()
        val sizes = arguments.getString("sizes", "100,300,500").split(',').map { it.trim().toInt() }
        require(sizes.all { it in 1..500 })
        val repeat500 = arguments.getString("repeats500", "3").toInt().coerceIn(1, 3)
        val sampleFolder = File(context.filesDir, "benchmark-samples-v2").apply { mkdirs() }
        val reportFolder = File(context.filesDir, "benchmark-reports").apply { mkdirs() }
        val externalEvidence = context.getExternalFilesDir("benchmark-evidence")
            ?: throw IOException("应用专属外部存储不可用，无法导出性能证据。")
        val externalReports = File(externalEvidence, "reports").apply { mkdirs() }
        val externalResults = File(externalEvidence, "results").apply { mkdirs() }
        if (!externalReports.isDirectory || !externalResults.isDirectory) {
            throw IOException("无法创建应用专属性能证据目录，请检查可用空间。")
        }
        val report = File(reportFolder, "summary-${System.currentTimeMillis()}.csv")
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        report.writeText("# SYNTHETIC numbered 4000x3000 JPEGs; not camera-photo certification\n# build_type=${BuildConfig.BUILD_TYPE};debuggable=${BuildConfig.DEBUG};version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE});sample_version=2\n# device=${Build.MANUFACTURER} ${Build.MODEL};sdk=${Build.VERSION.SDK_INT};ram_bytes=${memory.totalMem};heap_limit_bytes=${Runtime.getRuntime().maxMemory()};fingerprint=${Build.FINGERPRINT}\n# source_io_cache=warm_or_unknown_after_sample_generation;no_OS_cold_cache_claim;first_conversion_is_separately_labeled;sample_creation_excluded_from_elapsed\ncount,repeat,converter_invocation,elapsed_ms,file_bytes,pages,task_id\n")
        streamMirror(report, File(externalReports, report.name))
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "External evidence mirror=${externalEvidence.absolutePath}; copying is excluded from converter elapsed\n")
        })
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Generating deterministic 12MP synthetic numbered samples; report=${report.absolutePath}\n") })
        val items = createSamples(sampleFolder, sizes.max())
        var converterInvocation = 0
        for (count in sizes) {
            for (repeat in 1..if (count == 500) repeat500 else 1) {
                val taskId = "benchmark-$count-$repeat-${System.currentTimeMillis()}"
                converterInvocation++
                var lastReported = 0
                val started = SystemClock.elapsedRealtime()
                val output = ImageConverter(context).convert(items.take(count), OutputSettings(fileName = "$taskId.pdf"), taskId) { completed, _, _ ->
                    if (completed > 0 && completed % 50 == 0 && completed != lastReported) {
                        lastReported = completed
                        instrumentation.sendStatus(0, android.os.Bundle().apply {
                            putString("stream", "Batch $count run $repeat: $completed/$count\n")
                        })
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                val renders = mutableListOf<File>()
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertEquals(count, renderer.pageCount)
                        listOf(0, count / 2, count - 1).distinct().forEach { index ->
                            renderer.openPage(index).use { page ->
                                assertTrue("Auto direction must be landscape", page.width > page.height)
                                val bitmap = Bitmap.createBitmap(420, 297, Bitmap.Config.ARGB_8888)
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    // A centered solid marker encodes page order independently of the page count.
                                    assertColorClose(markerColor(index + 1), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                                    val render = File(reportFolder, "$taskId-page-${index + 1}.png")
                                    render.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    renders += render
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
                val outputBytes = output.length()
                report.appendText("$count,$repeat,$converterInvocation,$elapsed,$outputBytes,$count,$taskId\n")
                val measurements = File(reportFolder, "$taskId-measurement.csv")
                File(output.parentFile, "measurement.csv").copyTo(measurements, overwrite = true)
                // These copies happen after timing and successful independent page checks. A
                // completed earlier round remains exportable even if a later round is interrupted.
                streamMirror(report, File(externalReports, report.name))
                streamMirror(measurements, File(externalReports, measurements.name))
                renders.forEach { render -> streamMirror(render, File(externalReports, render.name)) }
                if (count == 500) {
                    // Retain one external, fully copied 500-page PDF, replacing only this test's
                    // latest result. Never accumulate one large external PDF for every repeat.
                    streamMirror(output, File(externalResults, "latest.pdf"))
                    File(externalResults, "latest-result.txt").writeText(
                        "task_id=$taskId\ncount=$count\nrepeat=$repeat\nbytes=$outputBytes\nsample_version=2\n" +
                            "source_private_path=${output.absolutePath}\ncopy_excluded_from_elapsed=true\n")
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Batch $count run $repeat evidence mirrored to ${externalEvidence.absolutePath}\n")
                })
                // Preserve only the final result for independent host parsing; prevent 3 large PDF copies.
                if (count != sizes.last() || repeat < if (count == 500) repeat500 else 1) output.parentFile?.deleteRecursively()
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Batch $count run $repeat completed in $elapsed ms; $outputBytes bytes\n") })
            }
        }
        sampleFolder.deleteRecursively()
    }

    /** Bounded-buffer copy; publish only after both streams close, retaining the old target meanwhile. */
    private fun streamMirror(source: File, target: File): Unit {
        val temporary = File(target.parentFile, "${target.name}.partial")
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.fd.sync()
                }
            }
            if (temporary.length() != source.length()) throw IOException("性能证据复制不完整：${source.name}")
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private fun createSamples(folder: File, count: Int): List<ImageEntry> {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        try {
            return (1..count).map { number ->
                val file = File(folder, String.format(Locale.ROOT, "图片_%03d.jpeg", number))
                if (!file.isFile) {
                    paint.shader = LinearGradient(0f, 0f, 4000f, 3000f, intArrayOf(Color.rgb(21, 53, 86), Color.rgb(211, 164, 68), Color.rgb(42, 108, 77)), null, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, 4000f, 3000f, paint); paint.shader = null
                    // Deterministic detailed texture with many boundaries, unlike a blank-page benchmark.
                    for (y in 0 until 3000 step 60) for (x in 0 until 4000 step 60) {
                        val hash = (x * 73856093 xor y * 19349663 xor number * 83492791)
                        paint.color = Color.argb(140, (hash ushr 16) and 255, (hash ushr 8) and 255, hash and 255)
                        canvas.drawCircle(x + 30f, y + 30f, 18f + (hash and 15), paint)
                    }
                    paint.color = markerColor(number)
                    canvas.drawRect(1700f, 1200f, 2300f, 1800f, paint)
                    // Nine big MSB-first cells encode 1..500 uniquely for every-page host validation.
                    // Keep this separate from the center color marker and human-readable page label.
                    paint.color = Color.WHITE
                    canvas.drawRect(1000f, 2100f, 3000f, 2600f, paint)
                    for (bit in 0 until 9) {
                        paint.color = if (((number shr (8 - bit)) and 1) == 1) Color.BLACK else Color.WHITE
                        val left = 1100f + bit * 200f
                        canvas.drawRect(left, 2200f, left + 200f, 2500f, paint)
                    }
                    paint.color = Color.WHITE; paint.textSize = 240f
                    canvas.drawText("PAGE $number / $count", 200f, 400f, paint)
                    file.outputStream().buffered().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                }
                ImageEntry("benchmark-$number", Uri.fromFile(file).toString(), file.name, 4000, 3000, originalOrder = number.toLong())
            }
        } finally { bitmap.recycle() }
    }

    private fun markerColor(number: Int): Int = Color.rgb(40 + number * 37 % 160, 40 + number * 61 % 160, 40 + number * 83 % 160)
    private fun assertColorClose(expected: Int, actual: Int) {
        assertTrue("Page marker mismatch: expected=$expected actual=$actual", kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 15 && kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 15 && kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 15)
    }
}
