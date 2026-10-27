package cn.local.imagepdf.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MAX_IMAGES: Int = 500

enum class PageSize { A4, A5, LETTER, IMAGE_RATIO }
enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }
enum class LayoutMode { FIT, FILL }
enum class Quality(val dpi: Int, val jpegQuality: Int) {
    HIGH(200, 90), STANDARD(150, 85), SMALL(100, 75)
}

data class OutputSettings(
    val fileName: String = defaultFileName(),
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.AUTO,
    val layout: LayoutMode = LayoutMode.FIT,
    val marginMm: Int = 10,
    val quality: Quality = Quality.STANDARD,
    val pageNumbers: Boolean = false
)

data class ImageEntry(
    val id: String,
    val uri: String,
    val name: String,
    val width: Int,
    val height: Int,
    val exifOrientation: Int = 1,
    val rotation: Int = 0,
    val originalOrder: Long = 0,
    val takenAt: Long? = null,
    val error: String? = null,
    val originalUri: String? = null
)

data class PdfRect(val left: Double, val top: Double, val width: Double, val height: Double)
data class PageLayout(
    val pageWidth: Double,
    val pageHeight: Double,
    val imageRect: PdfRect,
    val contentRect: PdfRect
)

fun defaultFileName(): String = "图片合并_" +
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)) + ".pdf"

/** The output name is only a display name; it is never used as a private directory path. */
fun sanitizeFileName(input: String): String {
    var name = input.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.', ' ')
    while (name.endsWith(".pdf", ignoreCase = true)) name = name.dropLast(4).trimEnd('.', ' ')
    name = name.take(120).trimEnd('.', ' ')
    if (name.isBlank()) name = "图片合并"
    return "$name.pdf"
}

/** Case-insensitive natural order without parsing digit runs into bounded integers. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i] in '0'..'9' && b[j] in '0'..'9') {
            val startA = i
            val startB = j
            while (i < a.length && a[i] in '0'..'9') i++
            while (j < b.length && b[j] in '0'..'9') j++
            var significantA = startA
            var significantB = startB
            while (significantA < i - 1 && a[significantA] == '0') significantA++
            while (significantB < j - 1 && b[significantB] == '0') significantB++
            val lengthOrder = (i - significantA).compareTo(j - significantB)
            if (lengthOrder != 0) return lengthOrder
            for (k in 0 until i - significantA) {
                val order = a[significantA + k].compareTo(b[significantB + k])
                if (order != 0) return order
            }
            // Numerically equal runs remain equal, allowing stable import-order fallback.
        } else {
            val order = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (order != 0) return order
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

fun remainingImageSlots(current: Int): Int {
    require(current in 0..MAX_IMAGES) { "当前图片数量必须为 0～500" }
    return MAX_IMAGES - current
}

fun validatePageCount(count: Int) {
    require(count in 1..MAX_IMAGES) { "请选择 1～500 张图片" }
}
