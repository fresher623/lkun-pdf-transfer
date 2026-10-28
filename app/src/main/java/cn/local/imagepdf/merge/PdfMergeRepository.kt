package cn.local.imagepdf.merge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import cn.local.imagepdf.core.sanitizeFileName
import cn.local.imagepdf.ui.ResultUi
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PdfMergeLimits {
    const val MAX_FILES = 50
    const val MAX_PAGES = 1000
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 512L * 1024 * 1024
    const val MAX_SOURCE_SCRATCH_BYTES = 256L * 1024 * 1024
    const val MAX_DESTINATION_SCRATCH_BYTES = 512L * 1024 * 1024
    const val MAX_SCRATCH_BYTES = MAX_SOURCE_SCRATCH_BYTES + MAX_DESTINATION_SCRATCH_BYTES
    const val MAX_OBJECTS_PER_FILE = 100_000
    const val MAX_TOTAL_OBJECTS = 200_000
    internal const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024
}

class PdfMergeException(val entryId: String?, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A conservative, offline page-object merger. It accepts basic page PDFs only; unsupported
 * document semantics are explicitly rejected rather than silently discarded or rasterized.
 * Disk scratch is bounded. COS object metadata still grows with document complexity, so the
 * file/page/object limits are guardrails, not a promise of constant memory or 4-GB certification.
 */
class PdfMergeRepository(context: Context) {
    private val app = context.applicationContext
    private val sources = File(app.filesDir, "merge-sources").apply { mkdirs() }
    private val mutex = Mutex()
    private var lastPressureGcAt = -1L

    init { PDFBoxResourceLoader.init(app) }

    suspend fun importUris(
        uris: List<Uri>, existing: List<MergeEntry>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<MergeEntry> = withContext(Dispatchers.IO) { mutex.withLock {
        if (existing.size + uris.size > PdfMergeLimits.MAX_FILES) {
            throw PdfMergeException(null, "一个任务最多 50 份 PDF；当前还可添加 ${maxOf(0, PdfMergeLimits.MAX_FILES - existing.size)} 份，请减少选择。")
        }
        var totalBytes = existing.sumOf { it.bytes.coerceAtLeast(0) }
        var totalPages = existing.sumOf { it.pages.coerceAtLeast(0) }
        val added = ArrayList<MergeEntry>(uris.size)
        try {
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                val source = File(sources, "$id.pdf")
                val copying = File(sources, "$id.copying")
                val scratch = File(app.cacheDir, "pdf-merge-import/$id").apply { mkdirs() }
                var displayName = "文档_${existing.size + index + 1}.pdf"
                try {
                    var declaredSize: Long? = null
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameColumn >= 0) displayName = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: displayName
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                        }
                    }
                    if ((declaredSize ?: 0) > PdfMergeLimits.MAX_FILE_BYTES) throw PdfMergeException(id, "单份 PDF 不能超过 256 MiB，请先缩小文件。")
                    copySource(uri, copying, totalBytes, id)
                    requirePdfEnvelope(copying, id)
                    val geometry = withStrictDocument(copying, scratch) { document -> inspectDocument(document, id) }
                    if (totalPages + geometry.size > PdfMergeLimits.MAX_PAGES) throw PdfMergeException(id, "合计页数不能超过 1000 页，请移除部分 PDF。")
                    verifyWithAndroid(copying, geometry, id)
                    currentCoroutineContext().ensureActive()
                    if (!copying.renameTo(source)) throw PdfMergeException(id, "无法保存 PDF 的应用内副本，请检查设备空间后重选。")
                    totalBytes += source.length()
                    totalPages += geometry.size
                    added += MergeEntry(id, uri.toString(), source.path, displayName, geometry.size, source.length())
                } catch (cancelled: CancellationException) {
                    source.delete()
                    throw cancelled
                } catch (failure: Exception) {
                    source.delete()
                    added += MergeEntry(id, uri.toString(), "", displayName, 0, 0,
                        readableFailure(failure, "PDF 无法读取或已损坏，请重新选择；云端文件请先下载到本地。"))
                } catch (_: OutOfMemoryError) {
                    source.delete()
                    added += MergeEntry(id, uri.toString(), "", displayName, 0, 0, "PDF 结构超过可用内存，请使用更简单或更小的文档。")
                } catch (_: StackOverflowError) {
                    source.delete()
                    added += MergeEntry(id, uri.toString(), "", displayName, 0, 0, "PDF 对象嵌套过深，首版无法安全处理，请重新导出基础 PDF。")
                } finally { copying.delete(); deleteOwnedTree(scratch, File(app.cacheDir, "pdf-merge-import")) }
                onProgress(index + 1, uris.size)
            }
            added
        } catch (failure: Throwable) {
            added.forEach { item -> controlledSource(item.sourcePath)?.delete() }
            throw failure
        }
    } }

    suspend fun merge(
        taskId: String, entries: List<MergeEntry>, outputName: String,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): ResultUi = withContext(Dispatchers.IO) { mutex.withLock {
        if (!taskId.matches(Regex("merge-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            throw PdfMergeException(null, "合并任务标识无效，请重新开始。")
        }
        if (entries.size !in 2..PdfMergeLimits.MAX_FILES) throw PdfMergeException(null, "请选择 2～50 份 PDF 后合并。")
        entries.firstOrNull { it.issue != null }?.let { throw PdfMergeException(it.id, "请先移除带异常标记的 PDF，再开始合并。") }
        val files = entries.map { item ->
            if (!item.id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
                throw PdfMergeException(item.id, "PDF 条目标识无效，请移除此项后重新选择。")
            }
            controlledSource(item.sourcePath)?.takeIf { it.isFile }
                ?: throw PdfMergeException(item.id, "应用内 PDF 副本已失效，请移除此项后重新选择。")
        }
        val totalBytes = files.sumOf { it.length() }
        val expectedPages = entries.sumOf { it.pages }
        if (files.any { it.length() > PdfMergeLimits.MAX_FILE_BYTES } || totalBytes > PdfMergeLimits.MAX_TOTAL_BYTES) {
            throw PdfMergeException(null, "输入超出限制：单份最多 256 MiB，合计最多 512 MiB。")
        }
        if (expectedPages !in 1..PdfMergeLimits.MAX_PAGES) throw PdfMergeException(null, "合计页数必须为 1～1000 页。")
        val resultDirectory = File(app.filesDir, "results/$taskId").apply { mkdirs() }
        val scratch = File(resultDirectory, "scratch").apply { mkdirs() }
        val partial = File(resultDirectory, "merging.partial")
        val output = File(resultDirectory, sanitizeFileName(outputName))
        if (output.exists()) throw PdfMergeException(null, "此任务已有生成结果，请新建合并任务。")
        val job = currentCoroutineContext()[Job]
        val checkpoint = { job?.ensureActive(); ensureHeapBudget(); Unit }
        try {
            ensureFreeSpace(resultDirectory, totalBytes * 2 + (files.maxOfOrNull { it.length() } ?: 0) + PdfMergeLimits.SPACE_RESERVE_BYTES)
            onProgress(0, expectedPages, "正在检查 PDF")
            val expectedGeometry = ArrayList<PageGeometry>(expectedPages)
            var copied = 0
            var objects = 0
            PDDocument(memorySetting(File(scratch, "destination"), PdfMergeLimits.MAX_DESTINATION_SCRATCH_BYTES)).use { destination ->
                entries.zip(files).forEach { (entry, file) ->
                    checkpoint()
                    requirePdfEnvelope(file, entry.id)
                    val sourceScratch = File(scratch, "source-${entry.id}").apply { mkdirs() }
                    try {
                        withStrictDocument(file, sourceScratch) { source ->
                            val geometry = inspectDocument(source, entry.id)
                            destination.document.version = maxOf(destination.version, source.version)
                            if (geometry.size != entry.pages || file.length() != entry.bytes) {
                                throw PdfMergeException(entry.id, "PDF 副本在导入后发生变化，请移除此项后重新选择。")
                            }
                            objects += source.document.xrefTable.size
                            if (objects > PdfMergeLimits.MAX_TOTAL_OBJECTS) throw PdfMergeException(entry.id, "PDF 对象总量过大，首版最多处理 20 万个对象，请减少文档数量。")
                            // A cloner per source releases its source-reference map when that source closes.
                            val cloner = PDFCloneUtility(destination)
                            source.pages.forEachIndexed { index, page ->
                                checkpoint()
                                ensureFreeSpace(resultDirectory, 16L * 1024 * 1024)
                                val pageDictionary = COSDictionary(page.cosObject).apply { removeItem(COSName.PARENT) }
                                val newPage = PDPage(cloner.cloneForNewDocument(pageDictionary) as COSDictionary)
                                newPage.mediaBox = copyRectangle(page.mediaBox)
                                newPage.cropBox = copyRectangle(page.cropBox)
                                newPage.rotation = page.rotation
                                newPage.resources = page.resources?.let { PDResources(cloner.cloneForNewDocument(it.cosObject) as COSDictionary) } ?: PDResources()
                                destination.addPage(newPage)
                                expectedGeometry += geometry[index]
                                copied++
                                onProgress(copied, expectedPages, "正在合并，第 $copied/$expectedPages 页")
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: PdfMergeException) { throw failure }
                    catch (failure: Exception) { throw PdfMergeException(entry.id, "PDF 复制失败，请检查该文档或使用基础 PDF 后重试。", failure) }
                    finally { deleteOwnedTree(sourceScratch, scratch) }
                }
                checkpoint()
                onProgress(copied, expectedPages, "正在写入 PDF")
                partial.outputStream().buffered(64 * 1024).use { target ->
                    BoundedPdfOutput(target, PdfMergeLimits.MAX_OUTPUT_BYTES) {
                        checkpoint(); ensureFreeSpace(resultDirectory, 16L * 1024 * 1024)
                    }.use { bounded -> destination.save(bounded) }
                }
            }
            checkpoint()
            onProgress(copied, expectedPages, "正在校验 PDF")
            verifyWithAndroid(partial, expectedGeometry, null)
            checkpoint()
            if (!partial.renameTo(output)) throw PdfMergeException(null, "无法完成合并文件写入，请检查设备空间后重试。")
            // The rename is the commit point. A late cancellation may leave this complete result;
            // the runtime can recover it by taskId and must never label it as a partial file.
            ResultUi(output.path, output.name, copied, output.length())
        } catch (failure: Throwable) {
            partial.delete()
            when (failure) {
                is OutOfMemoryError -> throw PdfMergeException(null, "PDF 结构超过可用内存，已清理未完成文件。请减少文档或使用更简单的 PDF。")
                is StackOverflowError -> throw PdfMergeException(null, "PDF 对象嵌套过深，已停止处理。请重新导出基础 PDF。")
                else -> throw failure
            }
        } finally { partial.delete(); deleteOwnedTree(scratch, resultDirectory) }
    } }

    suspend fun releaseUnusedSources(keep: List<MergeEntry>): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val kept = keep.mapNotNull { controlledSource(it.sourcePath)?.canonicalPath }.toSet()
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        sources.listFiles()?.forEach { file ->
            if (file.isFile && file.canonicalPath !in kept && file.lastModified() < cutoff &&
                file.name.matches(Regex("[0-9a-fA-F-]{36}\\.(pdf|copying)"))) file.delete()
        }
        val importScratch = File(app.cacheDir, "pdf-merge-import")
        importScratch.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { deleteOwnedTree(it, importScratch) }
    } }

    private suspend fun copySource(uri: Uri, target: File, existingBytes: Long, id: String) {
        var copied = 0L
        ensureFreeSpace(sources, PdfMergeLimits.SPACE_RESERVE_BYTES)
        (app.contentResolver.openInputStream(uri) ?: throw PdfMergeException(id, "PDF 无法打开，请检查文件是否已下载或已被删除。" )).use { input ->
            target.outputStream().buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (copied + count > PdfMergeLimits.MAX_FILE_BYTES) throw PdfMergeException(id, "单份 PDF 不能超过 256 MiB，请先缩小文件。")
                    if (existingBytes + copied + count > PdfMergeLimits.MAX_TOTAL_BYTES) throw PdfMergeException(id, "任务输入合计不能超过 512 MiB，请减少文档。")
                    if (copied % (4 * 1024 * 1024) < buffer.size) ensureFreeSpace(sources, PdfMergeLimits.SPACE_RESERVE_BYTES)
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
        }
        if (copied == 0L) throw PdfMergeException(id, "PDF 是空文件，请重新选择。")
    }

    private fun controlledSource(path: String): File? {
        if (path.isBlank()) return null
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == sources.canonicalFile && it.name.matches(Regex("[0-9a-fA-F-]{36}\\.pdf")) }
    }

    private suspend fun <T> withStrictDocument(file: File, scratchDirectory: File, block: suspend (PDDocument) -> T): T {
        val job = currentCoroutineContext()[Job]
        val deadline = SystemClock.elapsedRealtime() + 90_000
        val check = {
            job?.ensureActive()
            if (SystemClock.elapsedRealtime() > deadline) throw PdfMergeException(null, "PDF 解析超过 90 秒安全预算，请使用更简单的文档。")
            ensureHeapBudget()
        }
        val raw = RandomAccessBufferedFileInputStream(file)
        val source = CheckedRandomAccess(raw, check)
        var scratch: ScratchFile? = null
        var document: PDDocument? = null
        try {
            scratch = ScratchFile(memorySetting(scratchDirectory, PdfMergeLimits.MAX_SOURCE_SCRATCH_BYTES))
            val parser = RejectEncryptedParser(source, scratch)
            parser.setLenient(false)
            parser.parse()
            document = parser.pdDocument
            check()
            return block(document)
        } finally {
            try { document?.close() } finally { try { source.close() } finally { scratch?.close() } }
        }
    }

    private suspend fun inspectDocument(document: PDDocument, entryId: String): List<PageGeometry> {
        if (document.isEncrypted || document.document.trailer.containsKey(COSName.ENCRYPT)) throw PdfMergeException(entryId, "首版不支持加密 PDF，请先使用原软件解除密码和权限限制。")
        if (!document.version.isFinite() || document.version !in 1.0f..2.0f) throw PdfMergeException(entryId, "PDF 版本异常或高于 2.0，请先导出普通 PDF。")
        if (document.document.xrefTable.size > PdfMergeLimits.MAX_OBJECTS_PER_FILE) throw PdfMergeException(entryId, "这份 PDF 超过 10 万个对象的安全预算，请先简化文档。")
        val catalog = document.documentCatalog.cosObject
        val catalogIssue = catalog.keySet().firstOrNull { it.name !in setOf("Type", "Pages", "Version") }
        if (catalogIssue != null) throw PdfMergeException(entryId, featureMessage(catalogIssue.name))
        validateObjectGraph(document.document.trailer, entryId)
        val reported = document.numberOfPages
        if (reported !in 1..PdfMergeLimits.MAX_PAGES) throw PdfMergeException(entryId, "每份 PDF 必须包含 1～1000 页。")
        val result = ArrayList<PageGeometry>(reported)
        val pageKeys = setOf("Type", "Parent", "LastModified", "Resources", "MediaBox", "CropBox", "BleedBox", "TrimBox", "ArtBox", "Rotate", "Contents", "Group", "Thumb", "Metadata")
        for (page in document.pages) {
            currentCoroutineContext().ensureActive()
            if (result.size >= PdfMergeLimits.MAX_PAGES) throw PdfMergeException(entryId, "这份 PDF 超过 1000 页限制。")
            val unknown = page.cosObject.keySet().firstOrNull { it.name !in pageKeys }
            if (unknown != null) throw PdfMergeException(entryId, featureMessage(unknown.name))
            val media = page.mediaBox
            val crop = page.cropBox
            for (box in listOf(media, crop, page.bleedBox, page.trimBox, page.artBox)) {
                if (!listOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY).all { it.isFinite() } ||
                    box.width <= 0 || box.height <= 0 || box.width > 14_400 || box.height > 14_400) {
                    throw PdfMergeException(entryId, "PDF 页面尺寸异常或超过 200 英寸，首版无法处理。")
                }
            }
            val rotation = page.rotation.mod(360)
            if (rotation % 90 != 0) throw PdfMergeException(entryId, "PDF 页面旋转不是 90° 的整数倍，请重新导出。")
            result += PageGeometry(crop.width.toDouble(), crop.height.toDouble(), rotation)
        }
        if (result.size != reported) throw PdfMergeException(entryId, "PDF 页树与声明页数不一致，请修复文档后重新选择。")
        return result
    }

    private suspend fun validateObjectGraph(root: COSBase, id: String) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val queue = ArrayDeque<Pair<COSBase, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (value, depth) = queue.removeFirst()
            if (!seen.add(value)) continue
            if (seen.size > 300_000 || depth > 128) throw PdfMergeException(id, "PDF 对象图过大或嵌套过深，请简化文档后重试。")
            when (value) {
                is COSObject -> value.`object`?.let { queue.add(it to depth + 1) }
                is COSArray -> {
                    if (value.size() > 100_000) throw PdfMergeException(id, "PDF 数组超过安全预算，请简化文档。")
                    for (child in value) queue.add(child to depth + 1)
                }
                is COSDictionary -> {
                    val type = value.getNameAsString(COSName.TYPE)
                    val subtype = value.getNameAsString(COSName.SUBTYPE)
                    if (type in setOf("Sig", "DocTimeStamp") || value.containsKey(COSName.getPDFName("ByteRange"))) throw PdfMergeException(id, "首版不支持带数字签名的 PDF，合并会破坏签名，请使用未签名副本。")
                    if (type in setOf("EmbeddedFile", "Filespec") || subtype == "FileAttachment" || value.containsKey(COSName.getPDFName("EF"))) throw PdfMergeException(id, "首版不支持附件或文件包 PDF，请先导出普通页面 PDF。")
                    if (type in setOf("OCG", "OCMD") || value.containsKey(COSName.getPDFName("OC"))) throw PdfMergeException(id, "首版不支持可选图层 PDF，请先导出基础 PDF。")
                    if (value.containsKey(COSName.getPDFName("StructParent")) || value.containsKey(COSName.getPDFName("StructParents"))) throw PdfMergeException(id, "首版不支持带结构标签的 PDF，请先导出无标签基础 PDF。")
                    if (value.containsKey(COSName.getPDFName("OPI")) || subtype == "Form" && value.containsKey(COSName.getPDFName("Ref"))) throw PdfMergeException(id, "首版不支持外部文档或 OPI 引用，请先嵌入全部页面内容。")
                    if (type == "Action" || value.getNameAsString(COSName.S) in setOf("JavaScript", "Launch", "GoTo", "GoToR", "GoToE", "URI", "Named", "SubmitForm", "ResetForm", "ImportData", "SetOCGState", "Rendition", "Hide")) throw PdfMergeException(id, "首版不支持链接或交互动作，请先导出无交互 PDF。")
                    if (subtype in setOf("PS", "RichMedia", "3D", "Movie", "Sound", "Screen") ||
                        value.containsKey(COSName.getPDFName("JS")) || value.getNameAsString(COSName.S) == "JavaScript") throw PdfMergeException(id, "首版不支持脚本或多媒体 PDF，请先导出无交互 PDF。")
                    if (value is COSStream && value.containsKey(COSName.F)) throw PdfMergeException(id, "首版不支持引用外部文件的 PDF，请先嵌入全部内容。")
                    if (value.keySet().size > 10_000) throw PdfMergeException(id, "PDF 字典超过安全预算，请简化文档。")
                    value.values.forEach { queue.add(it to depth + 1) }
                }
            }
        }
    }

    private suspend fun verifyWithAndroid(file: File, expected: List<PageGeometry>, entryId: String?) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount != expected.size) throw PdfMergeException(entryId, "PDF 独立页数校验失败，请重新导出源文档。")
                val samples = setOf(0, expected.size / 2, expected.lastIndex)
                expected.forEachIndexed { index, geometry ->
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val width = if (geometry.rotation % 180 == 0) geometry.width else geometry.height
                        val height = if (geometry.rotation % 180 == 0) geometry.height else geometry.width
                        if (abs(page.width - width) > 2 || abs(page.height - height) > 2) throw PdfMergeException(entryId, "PDF 独立页面尺寸校验失败，首版无法可靠保留此文档的页框或旋转。")
                        if (index in samples) {
                            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            finally { bitmap.recycle() }
                        }
                    }
                }
            }
        }
    }

    private fun requirePdfEnvelope(file: File, id: String?) {
        if (file.length() < 20 || file.length() > PdfMergeLimits.MAX_FILE_BYTES && !file.name.endsWith(".partial")) throw PdfMergeException(id, "PDF 大小无效或超过限制，请重新选择。")
        RandomAccessFile(file, "r").use { source ->
            val header = ByteArray(5).also { source.readFully(it) }
            if (header.toString(Charsets.US_ASCII) != "%PDF-") throw PdfMergeException(id, "文件不是标准 PDF，请选择真正的 .pdf 文档。")
            val tailSize = minOf(4096, source.length().toInt())
            source.seek(source.length() - tailSize)
            val tail = ByteArray(tailSize).also { source.readFully(it) }.toString(Charsets.ISO_8859_1)
            if (!tail.trimEnd(' ', '\t', '\r', '\n', '\u0000').endsWith("%%EOF")) throw PdfMergeException(id, "PDF 文件结尾不完整或含异常尾随数据，请重新下载或导出。")
        }
    }

    private fun memorySetting(directory: File, limit: Long): MemoryUsageSetting {
        directory.mkdirs()
        return MemoryUsageSetting.setupTempFileOnly(limit).setTempDir(directory)
    }

    private fun ensureFreeSpace(directory: File, needed: Long) {
        if (StatFs(directory.path).availableBytes < needed) throw PdfMergeException(null, "应用存储空间不足，请释放空间后重试；需要同时容纳输入副本、临时数据和完整结果。")
    }

    private fun ensureHeapBudget() {
        val runtime = Runtime.getRuntime()
        val budget = (runtime.maxMemory() * 0.70).toLong()
        fun occupied() = runtime.totalMemory() - runtime.freeMemory()
        if (occupied() <= budget) return
        // Occupancy includes unreachable objects from already closed source documents.
        // Give ART one best-effort collection only after crossing the unchanged budget,
        // then fail closed if pressure remains. Dense parser checks must not request GC
        // routinely or spin waiting for memory; repeated pressure inside one second fails.
        val now = SystemClock.elapsedRealtime()
        if (lastPressureGcAt < 0 || now - lastPressureGcAt >= 1_000L) {
            lastPressureGcAt = now
            runtime.gc()
        }
        if (occupied() > budget) {
            throw PdfMergeException(null, "PDF 结构接近应用内存预算，请减少文档数量或简化 PDF 后重试。")
        }
    }

    private fun featureMessage(key: String): String = when (key) {
        "AcroForm", "XFA" -> "首版不支持 AcroForm 或 XFA 表单，请先在原软件中导出无表单的普通 PDF。"
        "Perms", "DSS" -> "首版不支持数字签名或认证 PDF，请使用未签名副本。"
        "Collection", "AF", "Names" -> "首版不支持附件、文件包或命名交互项，请先导出普通页面 PDF。"
        "Outlines", "PageLabels", "Dests" -> "首版不支持书签、链接目标或自定义页标签，请先导出基础 PDF。"
        "StructTreeRoot", "MarkInfo", "StructParents" -> "首版不支持带结构标签的 PDF，请先导出无标签的基础 PDF。"
        "Annots", "AA", "OpenAction" -> "首版不支持注释、链接或交互动作，请先导出无交互的普通 PDF。"
        "OCProperties" -> "首版不支持可选图层 PDF，请先导出基础 PDF。"
        "Metadata", "Lang", "ViewerPreferences", "PageLayout", "PageMode", "Extensions" -> "这份 PDF 含首版尚未支持的文档级设置，请先导出仅包含普通页面的基础 PDF。"
        else -> "这份 PDF 含首版尚未支持的页面或文档功能，请先导出无交互的基础 PDF。"
    }

    private fun readableFailure(failure: Exception, fallback: String): String = when (failure) {
        is PdfMergeException -> failure.message ?: fallback
        is SecurityException -> "PDF 读取权限已失效，请重新选择文件。"
        else -> fallback
    }

    private fun copyRectangle(rectangle: PDRectangle) = PDRectangle().apply {
        lowerLeftX = rectangle.lowerLeftX; lowerLeftY = rectangle.lowerLeftY
        upperRightX = rectangle.upperRightX; upperRightY = rectangle.upperRightY
    }

    private fun deleteOwnedTree(target: File, owner: File) {
        val canonical = target.canonicalFile
        val root = owner.canonicalFile
        if (canonical != root && canonical.path.startsWith(root.path + File.separator)) canonical.deleteRecursively()
    }

    private data class PageGeometry(val width: Double, val height: Double, val rotation: Int)

    private class RejectEncryptedParser(source: RandomAccessRead, scratch: ScratchFile) : PDFParser(source, scratch) {
        override fun parseXref(startXRefOffset: Long): COSDictionary {
            val trailer = super.parseXref(startXRefOffset)
            val encryption = trailer.getItem(COSName.ENCRYPT)
            if (encryption != null && encryption !is COSNull) throw PdfMergeException(null, "首版不支持加密 PDF，请先解除密码和权限限制。")
            if (document.xrefTable.size > PdfMergeLimits.MAX_OBJECTS_PER_FILE) throw PdfMergeException(null, "PDF 超过 10 万个对象的安全预算，请简化文档。")
            return trailer
        }
    }

    private class CheckedRandomAccess(private val delegate: RandomAccessRead, private val checkpoint: () -> Unit) : RandomAccessRead by delegate {
        private var operations = 0
        private fun check(length: Int = 1) { if (++operations % 128 == 1 || length >= 16 * 1024) checkpoint() }
        override fun read(): Int { check(); return delegate.read() }
        override fun read(buffer: ByteArray): Int { check(buffer.size); return delegate.read(buffer) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(length); return delegate.read(buffer, offset, length) }
        override fun seek(position: Long) { checkpoint(); delegate.seek(position) }
        override fun readFully(length: Int): ByteArray {
            checkpoint()
            if (length < 0 || length > 16 * 1024 * 1024) throw PdfMergeException(null, "PDF 单次解析分配超过 16 MiB 安全预算，请简化文档。")
            return delegate.readFully(length)
        }
    }

    private class BoundedPdfOutput(private val target: OutputStream, private val limit: Long, private val checkpoint: () -> Unit) : OutputStream() {
        private var written = 0L
        private var nextCheckpoint = 0L
        private fun check() {
            if (written >= nextCheckpoint) { checkpoint(); nextCheckpoint = written + 64 * 1024 }
        }
        override fun write(value: Int) {
            check()
            if (written + 1 > limit) throw PdfMergeException(null, "合并结果超过 512 MiB 输出限制，请减少文档。")
            target.write(value); written++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check()
            if (written + length > limit) throw PdfMergeException(null, "合并结果超过 512 MiB 输出限制，请减少文档。")
            target.write(buffer, offset, length); written += length
        }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
}
