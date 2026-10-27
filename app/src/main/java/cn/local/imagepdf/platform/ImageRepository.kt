package cn.local.imagepdf.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import cn.local.imagepdf.core.ImageEntry
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ImageProcessingException(val itemId: String, message: String) : IOException(message)

/** Sources are either persisted document URIs or controlled private, streamed copies. */
class ImageRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val sources = File(app.filesDir, "sources").apply { mkdirs() }
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val thumbnailMutex = Mutex()
    private val sourceMutex = Mutex()
    private val recentSources = HashMap<String, Long>()

    suspend fun importImages(
        uris: List<Uri>, startOrder: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<ImageEntry> = withContext(Dispatchers.IO) { sourceMutex.withLock {
        val imported = ArrayList<ImageEntry>(uris.size)
        try {
            uris.forEachIndexed { index, original ->
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                var name = "图片_${startOrder + index + 1}"
                var local: Uri = original
                try {
                    resolver.query(original, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) name = it.getString(0)?.takeIf(String::isNotBlank) ?: name
                    }
                    local = persistentOrCopy(original, id)
                    validateImageSignature(local)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    input(local).use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw IOException("无法解码这张图片，请重新选择 JPEG、PNG 或设备支持的静态图片。")
                    }
                    // Header validation uses Long; exceptionally large dimensions remain sampled later.
                    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > 4_000_000_000L) {
                        throw IOException("图片像素尺寸异常，请缩小图片后重新选择。")
                    }
                    validateSmallDecode(local, bounds.outWidth, bounds.outHeight)
                    var orientation = 1
                    var timestamp: Long? = null
                    try {
                        input(local).use { source ->
                            val exif = ExifInterface(source)
                            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).coerceIn(1, 8)
                            timestamp = readExifTimestamp(exif)
                        }
                    } catch (_: IOException) {
                        // Missing/broken optional EXIF does not make otherwise decodable pixels invalid.
                    }
                    imported += ImageEntry(id, local.toString(), name, bounds.outWidth, bounds.outHeight,
                        orientation, 0, startOrder + index, timestamp, null, originalUri = original.toString())
                } catch (cancelled: CancellationException) {
                    File(sources, "$id.source").delete()
                    throw cancelled
                } catch (_: OutOfMemoryError) {
                    imported += ImageEntry(id, local.toString(), name, 0, 0, originalOrder = startOrder + index,
                        error = "图片解码超过可用内存，请缩小原图后重新选择。", originalUri = original.toString())
                } catch (failure: Exception) {
                    imported += ImageEntry(id, local.toString(), name, 0, 0, originalOrder = startOrder + index,
                        error = when (failure) {
                            is SecurityException -> "图片读取权限已失效，请移除此项后重新选择。"
                            is IOException -> failure.message ?: "图片无法读取，请检查来源或重选。"
                            else -> "图片无法读取，可能尚未下载到本地，请重选或稍后再试。"
                        }, originalUri = original.toString())
                }
                onProgress(index + 1, uris.size)
            }
            imported
        } catch (cancelled: CancellationException) {
            imported.forEach { deletePrivateSource(it.uri) }
            throw cancelled
        }
    } }

    private suspend fun persistentOrCopy(uri: Uri, id: String): Uri {
        if (uri.scheme == "content") {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                    recentSources[uri.toString()] = System.currentTimeMillis()
                    return uri
                }
            } catch (_: SecurityException) { /* Photo Picker providers may not support persistent grants. */ }
        }
        val target = File(sources, "$id.source")
        try {
            input(uri).use { source ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (copied % (4 * 1024 * 1024) < buffer.size &&
                            StatFs(sources.path).availableBytes < 32L * 1024 * 1024) {
                            throw IOException("应用存储空间不足，请释放空间后重新导入。")
                        }
                        output.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            return Uri.fromFile(target)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun input(uri: Uri) = resolver.openInputStream(uri)
        ?: throw IOException("图片无法打开，请检查文件是否已删除或尚未下载。")

    private fun readExifTimestamp(exif: ExifInterface): Long? {
        val format = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        val tags = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_DATETIME to ExifInterface.TAG_OFFSET_TIME
        )
        return tags.firstNotNullOfOrNull { (dateTag, offsetTag) ->
            val date = exif.getAttribute(dateTag)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@firstNotNullOfOrNull null
            runCatching {
                val local = LocalDateTime.parse(date, format)
                val offset = exif.getAttribute(offsetTag)?.trim()?.takeIf { it.isNotEmpty() }
                if (offset != null) local.toInstant(ZoneOffset.of(offset)).toEpochMilli()
                else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }

    private suspend fun validateSmallDecode(uri: Uri, width: Int, height: Int) {
        currentCoroutineContext().ensureActive()
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
                val scale = (128.0 / maxOf(width, height)).coerceAtMost(1.0)
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        } else {
            var sample = 1
            while (maxOf(width, height).toLong() / sample > 128 && sample < (1 shl 29)) sample *= 2
            input(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) }
        }
        if (bitmap == null) throw IOException("图片内容损坏或格式不受设备支持，请重新选择。")
        bitmap.recycle()
        currentCoroutineContext().ensureActive()
    }

    private suspend fun validateImageSignature(uri: Uri) {
        input(uri).buffered().use { stream ->
            stream.mark(16)
            val first = ByteArray(12)
            var count = 0
            while (count < first.size) {
                val read = stream.read(first, count, first.size - count)
                if (read < 0) break
                count += read
            }
            if (count >= 3 && first.copyOfRange(0, 3).decodeToString() == "GIF") {
                throw IOException("暂不支持 GIF 动图，请先转换为静态 JPEG 或 PNG。")
            }
            if (count == 12 && first.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                first.copyOfRange(8, 12).decodeToString() == "WEBP") {
                val chunkHeader = ByteArray(8)
                var scanned = 0L
                // Read chunk structure without buffering the image or silently choosing animation frame 1.
                while (scanned < 256L * 1024 * 1024) {
                    currentCoroutineContext().ensureActive()
                    var filled = 0
                    while (filled < 8) {
                        val read = stream.read(chunkHeader, filled, 8 - filled)
                        if (read < 0) break
                        filled += read
                    }
                    if (filled != 8) break
                    val type = chunkHeader.copyOfRange(0, 4).decodeToString()
                    val length = (4..7).foldIndexed(0L) { index, result, byte ->
                        result or ((chunkHeader[byte].toLong() and 255) shl (index * 8))
                    }
                    if (type == "ANIM" || type == "ANMF") throw IOException("暂不支持动态 WebP，请先导出静态图片。")
                    if (type == "VP8X" && length > 0) {
                        val flags = stream.read()
                        if (flags < 0) throw IOException("WebP 文件不完整，请重新选择。")
                        if (flags and 0x02 != 0) throw IOException("暂不支持动态 WebP，请先导出静态图片。")
                        skipFully(stream, length - 1 + (length and 1))
                    } else skipFully(stream, length + (length and 1))
                    scanned += 8 + length + (length and 1)
                }
            }
            if (count >= 8 && first.take(8) == listOf(137.toByte(), 80.toByte(), 78.toByte(), 71.toByte(), 13.toByte(), 10.toByte(), 26.toByte(), 10.toByte())) {
                stream.reset()
                skipFully(stream, 8)
                val chunk = ByteArray(8)
                var scanned = 0L
                while (scanned < 256L * 1024 * 1024) {
                    currentCoroutineContext().ensureActive()
                    var filled = 0
                    while (filled < 8) {
                        val read = stream.read(chunk, filled, 8 - filled)
                        if (read < 0) break
                        filled += read
                    }
                    if (filled != 8) break
                    val length = (0..3).fold(0L) { result, index -> (result shl 8) or (chunk[index].toLong() and 255) }
                    val type = chunk.copyOfRange(4, 8).decodeToString()
                    if (type == "acTL") throw IOException("暂不支持动态 PNG，请先导出静态图片。")
                    if (type == "IDAT" || type == "IEND") break
                    skipFully(stream, length + 4)
                    scanned += length + 12
                }
            }
        }
    }

    private suspend fun skipFully(stream: java.io.InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = stream.skip(remaining.coerceAtMost(64 * 1024))
            if (skipped > 0) remaining -= skipped
            else if (stream.read() < 0) throw IOException("图片文件已截断，请重新选择。")
            else remaining--
        }
    }

    suspend fun loadThumbnail(item: ImageEntry, maxEdge: Int = 320): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${item.id}:${item.rotation}:$maxEdge"
        thumbnails.get(key) ?: thumbnailMutex.withLock {
            thumbnails.get(key) ?: loadPreview(item, maxEdge)?.also { thumbnails.put(key, it) }
        }
    }

    suspend fun loadPreview(item: ImageEntry, maxEdge: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        if (item.error != null) return@withContext null
        try {
            BitmapPipeline(app).preview(item, maxEdge)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { null }
        catch (_: Exception) { null }
    }

    fun clearThumbnailCache() { thumbnails.evictAll() }

    /** Call only after draft/task state is saved; keep entries from the active task too. */
    suspend fun releaseUnusedSources(keep: List<ImageEntry>) = withContext(Dispatchers.IO) { sourceMutex.withLock {
        // A draft update may race immediately after a completed import. Retain recent sources for
        // 24 hours so stale snapshots cannot delete a newly copied source or a freshly granted URI.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val paths = keep.mapNotNull { item ->
            Uri.parse(item.uri).takeIf { it.scheme == "file" }?.path
        }.toSet()
        sources.listFiles()?.forEach { file -> if (file.absolutePath !in paths && file.lastModified() < cutoff) file.delete() }
        val contentUris = keep.map { it.uri }.toSet()
        resolver.persistedUriPermissions.forEach { permission ->
            if (permission.uri.toString() !in contentUris && permission.persistedTime < cutoff &&
                (recentSources[permission.uri.toString()] ?: 0) < cutoff) {
                try { resolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* Provider already revoked the grant. */ }
            }
        }
        recentSources.entries.removeAll { it.value < cutoff }
    } }

    private fun deletePrivateSource(uri: String) {
        Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let { path ->
            val file = File(path)
            if (file.parentFile?.canonicalPath == sources.canonicalPath) file.delete()
        }
    }
}
