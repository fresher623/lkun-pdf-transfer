package cn.local.imagepdf.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

interface PdfEngine : Closeable {
    fun addPage(jpeg: File, pixelWidth: Int, pixelHeight: Int, layout: PageLayout, pageNumber: Int? = null)
    fun finish()
}

/**
 * A minimal PDF 1.4 writer. It streams each RGB JPEG from disk and retains only O(page count)
 * object offsets. It never retains Bitmaps, JPEG byte arrays, or whole-document PDF bytes.
 * close() only closes the stream; a cancelled conversion must not acquire a valid trailer.
 */
class StreamingPdfWriter(file: File, private val totalPages: Int) : PdfEngine {
    private val fileStream: FileOutputStream
    private val output: CountingOutputStream
    private val offsets: LongArray
    private var pagesWritten = 0
    private var finished = false
    private var closed = false

    init {
        validatePageCount(totalPages)
        offsets = LongArray(4 + totalPages * 3)
        fileStream = FileOutputStream(file)
        output = CountingOutputStream(BufferedOutputStream(fileStream, 64 * 1024))
        output.ascii("%PDF-1.4\n%âãÏÓ\n")
    }

    override fun addPage(jpeg: File, pixelWidth: Int, pixelHeight: Int, layout: PageLayout, pageNumber: Int?) {
        check(!closed && !finished) { "PDF 写入器已关闭" }
        check(pagesWritten < totalPages) { "输出页数超过快照" }
        require(pixelWidth > 0 && pixelHeight > 0) { "JPEG 尺寸无效" }
        require(pageNumber == null || pageNumber == pagesWritten + 1) { "页码必须与输出顺序一致" }
        validateLayout(layout)
        require(jpeg.isFile && jpeg.length() >= 4L) { "JPEG 临时文件不存在或不完整" }
        jpeg.inputStream().use { input ->
            require(input.read() == 0xff && input.read() == 0xd8) { "图片编码必须是 JPEG" }
        }

        val pageId = 4 + pagesWritten * 3
        val imageId = pageId + 1
        val contentId = pageId + 2
        val content = pageCommands(layout, pageNumber).toByteArray(Charsets.US_ASCII)
        startObject(pageId)
        output.ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(layout.pageWidth)} ${num(layout.pageHeight)}] " +
            "/Resources << /XObject << /Im1 $imageId 0 R >> /Font << /F1 3 0 R >> >> " +
            "/Contents $contentId 0 R >>\nendobj\n")
        startObject(imageId)
        output.ascii("<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length()} >>\nstream\n")
        jpeg.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        output.ascii("\nendstream\nendobj\n")
        startObject(contentId)
        output.ascii("<< /Length ${content.size} >>\nstream\n")
        output.write(content)
        output.ascii("endstream\nendobj\n")
        // Progress may only advance after all three objects for the page have been written.
        output.flush()
        pagesWritten++
    }

    override fun finish() {
        check(!closed && !finished) { "PDF 写入器已关闭" }
        check(pagesWritten == totalPages) { "PDF 未写入全部页面" }
        startObject(1)
        output.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        startObject(2)
        output.ascii("<< /Type /Pages /Count $totalPages /Kids [")
        repeat(totalPages) { output.ascii("${4 + it * 3} 0 R ") }
        output.ascii("] >>\nendobj\n")
        startObject(3)
        output.ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        val xrefOffset = output.count
        output.ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (id in 1 until offsets.size) {
            require(offsets[id] in 1..9_999_999_999L) { "PDF 文件超过当前引擎支持的大小" }
            output.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]))
        }
        output.ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        output.flush()
        fileStream.fd.sync()
        finished = true
    }

    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }

    private fun startObject(id: Int) {
        check(offsets[id] == 0L) { "PDF 对象被重复写入" }
        offsets[id] = output.count
        output.ascii("$id 0 obj\n")
    }

    private fun pageCommands(layout: PageLayout, pageNumber: Int?): String = buildString {
        val clip = layout.contentRect
        val image = layout.imageRect
        append("q\n1 1 1 rg\n0 0 ${num(layout.pageWidth)} ${num(layout.pageHeight)} re f\n")
        append("q\n${num(clip.left)} ${num(layout.pageHeight - clip.top - clip.height)} ${num(clip.width)} ${num(clip.height)} re W n\n")
        append("${num(image.width)} 0 0 ${num(image.height)} ${num(image.left)} ${num(layout.pageHeight - image.top - image.height)} cm\n/Im1 Do\nQ\n")
        if (pageNumber != null) {
            val text = pageNumber.toString()
            val x = (layout.pageWidth - text.length * 5.56) / 2
            val y = layout.pageHeight - clip.top - clip.height - 15.0
            require(y >= 0.0) { "页码需要独立底部区域" }
            append("0.28 0.28 0.28 rg\nBT /F1 10 Tf 1 0 0 1 ${num(x)} ${num(y)} Tm ($text) Tj ET\n")
        }
        append("Q\n")
    }

    private fun validateLayout(layout: PageLayout) {
        require(layout.pageWidth.isFinite() && layout.pageHeight.isFinite() &&
            layout.pageWidth in 1.0..14_400.0 && layout.pageHeight in 1.0..14_400.0) { "PDF 页面尺寸无效" }
        for (rect in listOf(layout.contentRect, layout.imageRect)) {
            require(listOf(rect.left, rect.top, rect.width, rect.height).all { it.isFinite() } &&
                rect.width > 0 && rect.height > 0) { "PDF 图片区域无效" }
        }
        val clip = layout.contentRect
        require(clip.left >= 0 && clip.top >= 0 && clip.left + clip.width <= layout.pageWidth + 0.001 &&
            clip.top + clip.height <= layout.pageHeight + 0.001) { "PDF 内容区域超出页面" }
    }

    private fun num(value: Double): String = String.format(Locale.ROOT, "%.5f", value)

    private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
        var count: Long = 0
            private set
        override fun write(b: Int) { target.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); count += len }
        override fun flush() = target.flush()
        override fun close() = target.close()
        fun ascii(value: String) = write(value.toByteArray(Charsets.ISO_8859_1))
    }
}
