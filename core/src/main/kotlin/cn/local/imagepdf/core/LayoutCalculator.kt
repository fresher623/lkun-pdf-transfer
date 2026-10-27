package cn.local.imagepdf.core

import kotlin.math.max
import kotlin.math.min

object LayoutCalculator {
    fun mmToPoints(mm: Double): Double = mm / 25.4 * 72.0

    /** Source dimensions must already account for all EXIF transforms and user rotation. */
    fun calculate(width: Int, height: Int, settings: OutputSettings): PageLayout {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        require(settings.marginMm in listOf(0, 5, 10, 15)) { "页边距必须为 0、5、10 或 15 毫米" }
        val margin = mmToPoints(settings.marginMm.toDouble())
        val footer = if (settings.pageNumbers) 22.0 else 0.0
        val (baseWidth, baseHeight) = when (settings.pageSize) {
            PageSize.A4 -> mmToPoints(210.0) to mmToPoints(297.0)
            PageSize.A5 -> mmToPoints(148.0) to mmToPoints(210.0)
            PageSize.LETTER -> 612.0 to 792.0
            PageSize.IMAGE_RATIO -> {
                val factor = mmToPoints(297.0) / max(width, height).toDouble()
                // A one-point minimum prevents degenerate PDF rectangles for extreme panoramas.
                (max(1.0, width * factor) + margin * 2) to
                    (max(1.0, height * factor) + margin * 2)
            }
        }
        val landscape = when (settings.orientation) {
            Orientation.AUTO -> width > height
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
        }
        // In proportional auto mode retain the precise source aspect, including square images.
        val swap = if (settings.pageSize == PageSize.IMAGE_RATIO && settings.orientation == Orientation.AUTO) {
            false
        } else {
            landscape != (baseWidth > baseHeight)
        }
        val pageWidth = if (swap) baseHeight else baseWidth
        val pageHeight = (if (swap) baseWidth else baseHeight) +
            if (settings.pageSize == PageSize.IMAGE_RATIO) footer else 0.0
        val contentWidth = pageWidth - margin * 2
        val contentHeight = pageHeight - margin * 2 - footer
        require(contentWidth > 0 && contentHeight > 0) { "当前页面无法容纳边距和页码，请调整设置" }
        val content = PdfRect(margin, margin, contentWidth, contentHeight)
        // Long images always use fit in proportional mode as required by V1.
        val scale = if (settings.layout == LayoutMode.FILL && settings.pageSize != PageSize.IMAGE_RATIO) {
            max(contentWidth / width, contentHeight / height)
        } else {
            min(contentWidth / width, contentHeight / height)
        }
        val drawWidth = width * scale
        val drawHeight = height * scale
        return PageLayout(
            pageWidth,
            pageHeight,
            PdfRect(margin + (contentWidth - drawWidth) / 2, margin + (contentHeight - drawHeight) / 2,
                drawWidth, drawHeight),
            content
        )
    }
}
