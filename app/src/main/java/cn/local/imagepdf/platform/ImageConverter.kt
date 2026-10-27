package cn.local.imagepdf.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.net.Uri
import cn.local.imagepdf.core.ImageEntry
import cn.local.imagepdf.core.LayoutCalculator
import cn.local.imagepdf.core.OutputSettings
import cn.local.imagepdf.core.StreamingPdfWriter
import cn.local.imagepdf.core.sanitizeFileName
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ImageConverter(context: Context) {
    private val app = context.applicationContext

    suspend fun convert(
        items: List<ImageEntry>, settings: OutputSettings, taskId: String,
        onProgress: (completed: Int, total: Int, stage: String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(items.size in 1..500) { "请选择 1～500 张图片。" }
        require(taskId.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "任务标识无效。" }
        items.firstOrNull { it.error != null }?.let { throw ImageProcessingException(it.id, "请先移除或重选异常图片，再生成 PDF。") }
        val directory = File(app.filesDir, "results/$taskId").apply { mkdirs() }
        val target = File(directory, sanitizeFileName(settings.fileName))
        val partial = File(directory, "generating.partial")
        val pageJpeg = File(directory, "page.jpeg")
        if (target.exists()) throw IOException("此任务已经有生成结果，请新建任务后再次转换。")
        val measurement = File(directory, "measurement.csv")
        val started = SystemClock.elapsedRealtime()
        var completed = 0
        try {
            currentCoroutineContext().ensureActive()
            if (StatFs(directory.path).availableBytes < 64L * 1024 * 1024) throw IOException("应用存储空间不足，请至少释放 64 MB 后重试；实际所需空间随图片变化。")
            onProgress(0, items.size, "准备中")
            // Re-check access after the user has edited the draft; an old grant may be revoked.
            items.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                onProgress(0, items.size, "正在检查图片，第 ${index + 1}/${items.size} 张")
                try {
                    app.contentResolver.openInputStream(Uri.parse(item.uri))?.use {
                        if (it.read() < 0) throw IOException("图片内容为空，请重新选择。")
                    } ?: throw IOException("图片无法打开，请重新选择。")
                } catch (failure: SecurityException) {
                    throw ImageProcessingException(item.id, "这张图片的读取权限已失效，请重新选择。")
                } catch (failure: IOException) {
                    throw ImageProcessingException(item.id, "这张图片无法读取，请确认已下载到本地后重新选择。")
                }
            }
            measurement.bufferedWriter().use { metrics ->
                metrics.appendLine("# device=${Build.MANUFACTURER} ${Build.MODEL};sdk=${Build.VERSION.SDK_INT};heap_limit_bytes=${Runtime.getRuntime().maxMemory()};dpi=${settings.quality.dpi};jpeg_quality=${settings.quality.jpegQuality}")
                metrics.appendLine("page,elapsed_ms,java_used_bytes,native_alloc_bytes,pss_kb,rss_kb,pdf_bytes")
                val pipeline = BitmapPipeline(app)
                StreamingPdfWriter(partial, items.size).use { writer ->
                    items.forEachIndexed { index, item ->
                        currentCoroutineContext().ensureActive()
                        onProgress(completed, items.size, "正在转换，第 ${index + 1}/${items.size} 页")
                        if (StatFs(directory.path).availableBytes < 16L * 1024 * 1024) throw IOException("应用存储空间不足，已停止生成，请释放空间后重试。")
                        val size = BitmapPipeline.orientedSize(item)
                        val layout = LayoutCalculator.calculate(size.first, size.second, settings)
                        val width = max(1, ceil(layout.imageRect.width / 72.0 * settings.quality.dpi).toInt())
                        val height = max(1, ceil(layout.imageRect.height / 72.0 * settings.quality.dpi).toInt())
                        val bitmap = try { pipeline.decode(item, width, height) }
                        catch (failure: SecurityException) { throw ImageProcessingException(item.id, "这张图片的读取权限已失效，请重新选择。") }
                        catch (failure: IOException) { throw ImageProcessingException(item.id, failure.message ?: "这张图片无法读取，请重新选择。") }
                        try {
                            currentCoroutineContext().ensureActive()
                            pageJpeg.outputStream().buffered().use { output ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, settings.quality.jpegQuality, output)) {
                                    throw ImageProcessingException(item.id, "图片编码失败，请缩小原图或重新选择。")
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            writer.addPage(pageJpeg, bitmap.width, bitmap.height, layout, if (settings.pageNumbers) index + 1 else null)
                        } finally { bitmap.recycle(); pageJpeg.delete() }
                        // Only successful addPage calls count as completed.
                        completed++
                        onProgress(completed, items.size, "正在转换，第 $completed/${items.size} 页")
                        val runtime = Runtime.getRuntime()
                        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                        val rss = try { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull() ?: -1L } }
                            catch (_: IOException) { -1L }
                        metrics.appendLine("$completed,${SystemClock.elapsedRealtime() - started},${runtime.totalMemory() - runtime.freeMemory()},${Debug.getNativeHeapAllocatedSize()},${memory.totalPss},$rss,${partial.length()}")
                        metrics.flush()
                    }
                    currentCoroutineContext().ensureActive()
                    onProgress(completed, items.size, "正在写入")
                    writer.finish()
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(completed, items.size, "正在校验")
            ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount != items.size) throw IOException("PDF 页数校验失败，请重新生成。")
                    listOf(0, items.size / 2, items.size - 1).distinct().forEach { index ->
                        currentCoroutineContext().ensureActive()
                        renderer.openPage(index).use { page ->
                            if (page.width <= 0 || page.height <= 0) throw IOException("PDF 页面校验失败，请重新生成。")
                            val preview = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            try { page.render(preview, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            finally { preview.recycle() }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(target)) throw IOException("无法完成私有结果写入，请检查应用存储空间。")
            target
        } catch (failure: Throwable) {
            partial.delete()
            pageJpeg.delete()
            // Keep small measurement data for diagnostics; never retain an incomplete PDF as a result.
            if (failure is OutOfMemoryError) {
                throw IOException("图片处理超过可用内存，任务已停止并清理未完成文件。请使用省空间质量或缩小原图后重试。")
            }
            throw failure
        } finally { pageJpeg.delete() }
    }
}
