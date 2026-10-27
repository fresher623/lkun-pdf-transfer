package cn.local.imagepdf.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import cn.local.imagepdf.core.ImageEntry
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The budget includes both the sampled decoder bitmap and its oriented/white destination. */
internal class BitmapPipeline(private val context: Context) {
    private val budgetBytes: Long = min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)

    suspend fun preview(item: ImageEntry, maxEdge: Int): Bitmap {
        val oriented = orientedSize(item)
        val factor = min(1.0, maxEdge.toDouble() / max(oriented.first, oriented.second))
        return decode(item, max(1, (oriented.first * factor).toInt()), max(1, (oriented.second * factor).toInt()))
    }

    suspend fun decode(item: ImageEntry, wantedWidth: Int, wantedHeight: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        if (item.width <= 0 || item.height <= 0) throw ImageProcessingException(item.id, "图片尺寸无效，请移除后重新选择。")
        val oriented = orientedSize(item)
        val requestedScale = min(1.0, min(wantedWidth.toDouble() / oriented.first, wantedHeight.toDouble() / oriented.second))
        val requestedWidth = max(1, ceil(item.width * requestedScale).toInt())
        val requestedHeight = max(1, ceil(item.height * requestedScale).toInt())
        var sample = 1
        while (sample < (1 shl 29) &&
            ((item.width.toLong() / (sample * 2) >= requestedWidth && item.height.toLong() / (sample * 2) >= requestedHeight))) {
            sample *= 2
        }
        // Do not silently reduce the selected quality to fit a device's heap budget.
        val sampledPixels = requestedWidth.toLong() * requestedHeight
        val destinationPixels = requestedWidth.toLong() * requestedHeight
        if ((sampledPixels + destinationPixels) * 4 > budgetBytes) {
            throw ImageProcessingException(item.id, "这张图片按所选质量解码会超过内存预算，请选择省空间质量或缩小原图。")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Density scaling lets the decoder reach the requested DPI between power-of-two samples.
            // The resulting bitmap does not exceed source resolution or allocate the entire source.
            val densityScale = requestedScale * sample
            if (densityScale < 0.999) {
                inScaled = true
                inDensity = 100_000
                inTargetDensity = max(1, (100_000 * densityScale).toInt())
            }
        }
        val decoded = context.contentResolver.openInputStream(Uri.parse(item.uri))?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw ImageProcessingException(item.id, "图片无法解码或读取权限已失效，请重选这张图片。")
        try {
            currentCoroutineContext().ensureActive()
            val transform = exifMatrix(item.exifOrientation).apply { postRotate(item.rotation.toFloat()) }
            val bounds = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
            transform.mapRect(bounds)
            transform.postTranslate(-bounds.left, -bounds.top)
            val finalScale = min(1f, min(wantedWidth / bounds.width(), wantedHeight / bounds.height()))
            transform.postScale(finalScale, finalScale)
            val width = max(1, (bounds.width() * finalScale).toInt())
            val height = max(1, (bounds.height() * finalScale).toInt())
            if (decoded.allocationByteCount.toLong() + width.toLong() * height * 4 > budgetBytes) {
                throw ImageProcessingException(item.id, "图片解码超过内存预算，请改用省空间质量。")
            }
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(result).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                currentCoroutineContext().ensureActive()
                return result
            } catch (failure: Throwable) { result.recycle(); throw failure }
        } finally { decoded.recycle() }
    }

    companion object {
        fun orientedSize(item: ImageEntry): Pair<Int, Int> {
            var swap = item.exifOrientation in 5..8
            if (item.rotation.mod(180) != 0) swap = !swap
            return if (swap) item.height to item.width else item.width to item.height
        }

        fun exifMatrix(orientation: Int): Matrix {
            val values = when (orientation) {
                2 -> floatArrayOf(-1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
                3 -> floatArrayOf(-1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
                4 -> floatArrayOf(1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
                5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
                6 -> floatArrayOf(0f,-1f,0f, 1f,0f,0f, 0f,0f,1f)
                7 -> floatArrayOf(0f,-1f,0f, -1f,0f,0f, 0f,0f,1f)
                8 -> floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
                else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
            }
            return Matrix().apply { setValues(values) }
        }
    }
}
