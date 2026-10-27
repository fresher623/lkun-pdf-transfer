package cn.local.imagepdf.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class CoreTest {
    @Test fun millimetresUsePdfPoints() {
        assertEquals(72.0, LayoutCalculator.mmToPoints(25.4), 0.00001)
        val layout = LayoutCalculator.calculate(1200, 1600, OutputSettings())
        assertEquals(595.27559, layout.pageWidth, 0.0001)
        assertEquals(841.88976, layout.pageHeight, 0.0001)
        assertEquals(28.34646, layout.contentRect.left, 0.0001)
    }

    @Test fun autoOrientationUsesFinalDimensions() {
        val portrait = LayoutCalculator.calculate(1200, 1600, OutputSettings())
        val landscape = LayoutCalculator.calculate(1600, 1200, OutputSettings())
        assertEquals(portrait.pageWidth, landscape.pageHeight, 0.00001)
        assertEquals(portrait.pageHeight, landscape.pageWidth, 0.00001)
    }

    @Test fun fixedOrientationOverridesImage() {
        val portrait = LayoutCalculator.calculate(1600, 1200, OutputSettings(orientation = Orientation.PORTRAIT))
        assertTrue(portrait.pageHeight > portrait.pageWidth)
        val landscape = LayoutCalculator.calculate(1200, 1600, OutputSettings(orientation = Orientation.LANDSCAPE))
        assertTrue(landscape.pageWidth > landscape.pageHeight)
    }

    @Test fun fitPreservesAspectAndStaysInsideContent() {
        val page = LayoutCalculator.calculate(300, 100, OutputSettings())
        val image = page.imageRect
        val content = page.contentRect
        assertEquals(3.0, image.width / image.height, 0.00001)
        assertTrue(image.left >= content.left && image.top >= content.top)
        assertTrue(image.width <= content.width + 0.001 && image.height <= content.height + 0.001)
    }

    @Test fun fillCropsOutsideContentWithoutStretch() {
        val page = LayoutCalculator.calculate(300, 100,
            OutputSettings(layout = LayoutMode.FILL, orientation = Orientation.PORTRAIT))
        assertEquals(3.0, page.imageRect.width / page.imageRect.height, 0.00001)
        assertTrue(page.imageRect.width > page.contentRect.width)
        assertEquals(page.contentRect.height, page.imageRect.height, 0.00001)
    }

    @Test fun letterAndA5Dimensions() {
        val letter = LayoutCalculator.calculate(1, 2, OutputSettings(pageSize = PageSize.LETTER))
        assertEquals(612.0, letter.pageWidth, 0.00001)
        assertEquals(792.0, letter.pageHeight, 0.00001)
        val a5 = LayoutCalculator.calculate(1, 2, OutputSettings(pageSize = PageSize.A5))
        assertEquals(LayoutCalculator.mmToPoints(148.0), a5.pageWidth, 0.00001)
    }

    @Test fun proportionalModeUsesPhysicalSize() {
        val page = LayoutCalculator.calculate(100, 200,
            OutputSettings(pageSize = PageSize.IMAGE_RATIO, marginMm = 10))
        assertEquals(LayoutCalculator.mmToPoints(297.0), page.contentRect.height, 0.00001)
        assertEquals(0.5, page.contentRect.width / page.contentRect.height, 0.00001)
    }

    @Test fun proportionalModeReservesFooterAfterOrientation() {
        for (orientation in Orientation.entries) {
            for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1 to 1)) {
                val page = LayoutCalculator.calculate(width, height,
                    OutputSettings(pageSize = PageSize.IMAGE_RATIO, orientation = orientation,
                        pageNumbers = true, layout = LayoutMode.FILL, marginMm = 0))
                assertTrue(page.contentRect.width > 0 && page.contentRect.height > 0)
                assertEquals(22.0, page.pageHeight - page.contentRect.height, 0.00001)
                assertTrue(page.imageRect.width <= page.contentRect.width + 0.001)
                assertTrue(page.imageRect.height <= page.contentRect.height + 0.001)
            }
        }
    }

    @Test fun pageNumberRegionDoesNotCoverImage() {
        val page = LayoutCalculator.calculate(1200, 1600, OutputSettings(pageNumbers = true))
        val plain = LayoutCalculator.calculate(1200, 1600, OutputSettings())
        assertEquals(22.0, plain.contentRect.height - page.contentRect.height, 0.00001)
        assertTrue(page.imageRect.top + page.imageRect.height <= page.contentRect.top + page.contentRect.height + 0.001)
    }

    @Test fun invalidSizesAndMarginsAreRejected() {
        expectFailure { LayoutCalculator.calculate(0, 50, OutputSettings()) }
        expectFailure { LayoutCalculator.calculate(50, -1, OutputSettings()) }
        expectFailure { LayoutCalculator.calculate(50, 50, OutputSettings(marginMm = 100)) }
    }

    @Test fun naturalOrderTreatsNumbersNumerically() {
        val sorted = listOf("10.jpg", "2.jpg", "1.jpg", "20.jpg").sortedWith(::naturalCompare)
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "20.jpg"), sorted)
        assertTrue(naturalCompare("IMG99999999999999999999999.jpg", "IMG100000000000000000000000.jpg") < 0)
    }

    @Test fun naturalOrderIsStableForCaseAndLeadingZeroes() {
        assertEquals(0, naturalCompare("IMG001.jpg", "img1.JPG"))
        assertEquals(0, naturalCompare("同名.jpg", "同名.jpg"))
        assertTrue(naturalCompare("1.jpg", "1a.jpg") < 0)
        assertTrue(naturalCompare("12a.jpg", "12b.jpg") < 0)
    }

    @Test fun namesAreSafeAndHaveOneExtension() {
        assertEquals("中文 文件.pdf", sanitizeFileName(" 中文 文件.PDF "))
        assertEquals("图片合并.pdf", sanitizeFileName(" .PDF "))
        assertEquals("a_b_c_d_e_f_g_h_i.pdf", sanitizeFileName("a\\b/c:d*e?f\"g<h>i"))
        assertEquals("x.pdf", sanitizeFileName("x.PDF.pdf"))
        assertTrue(sanitizeFileName("a".repeat(300)).length <= 124)
        assertTrue(defaultFileName().matches(Regex("图片合并_\\d{8}_\\d{6}\\.pdf")))
    }

    @Test fun imageLimitIncludesDuplicatesAnd500() {
        validatePageCount(1)
        validatePageCount(499)
        validatePageCount(500)
        assertEquals(500, remainingImageSlots(0))
        assertEquals(1, remainingImageSlots(499))
        assertEquals(0, remainingImageSlots(500))
        expectFailure { validatePageCount(0) }
        expectFailure { validatePageCount(501) }
        expectFailure { remainingImageSlots(501) }
    }

    @Test fun qualityPresetsChangeRealDpiAndEncoderValues() {
        assertEquals(200, Quality.HIGH.dpi)
        assertEquals(90, Quality.HIGH.jpegQuality)
        assertEquals(150, Quality.STANDARD.dpi)
        assertEquals(85, Quality.STANDARD.jpegQuality)
        assertEquals(100, Quality.SMALL.dpi)
        assertEquals(75, Quality.SMALL.jpegQuality)
    }

    @Test fun writerBuildsValidCrossReferenceAndPageObjects() = withTemp { dir ->
        val image = jpeg(dir)
        val pdf = File(dir, "three-pages.pdf")
        StreamingPdfWriter(pdf, 3).use { writer ->
            repeat(3) { index ->
                writer.addPage(image, 16, 24,
                    LayoutCalculator.calculate(16, 24, OutputSettings(pageNumbers = true)), index + 1)
            }
            writer.finish()
        }
        val bytes = pdf.readBytes()
        val source = bytes.toString(Charsets.ISO_8859_1)
        val startxref = Regex("startxref\\n(\\d+)\\n%%EOF").find(source)!!.groupValues[1].toInt()
        assertEquals("xref", source.substring(startxref, startxref + 4))
        val xref = source.substring(startxref).lineSequence().toList()
        assertEquals("0 13", xref[1])
        for (id in 1..12) {
            val offset = xref[id + 2].take(10).toInt()
            assertTrue("object $id", source.substring(offset).startsWith("$id 0 obj\n"))
        }
        assertTrue(source.contains("/Count 3 /Kids [4 0 R 7 0 R 10 0 R "))
        assertTrue(source.contains("(1) Tj") && source.contains("(3) Tj"))
        assertEquals(3, Regex("/Subtype /Image").findAll(source).count())
    }

    @Test fun writerDoesNotFinishCancelledDocument() = withTemp { dir ->
        val pdf = File(dir, "cancelled.pdf")
        val image = jpeg(dir)
        StreamingPdfWriter(pdf, 2).use {
            it.addPage(image, 16, 24, LayoutCalculator.calculate(16, 24, OutputSettings()))
        }
        assertFalse(pdf.readText(Charsets.ISO_8859_1).contains("%%EOF"))
    }

    @Test fun writerRejectsIncompleteOrAdditionalPages() = withTemp { dir ->
        val image = jpeg(dir)
        val page = LayoutCalculator.calculate(16, 24, OutputSettings())
        StreamingPdfWriter(File(dir, "incomplete.pdf"), 2).use { writer ->
            writer.addPage(image, 16, 24, page)
            expectFailure { writer.finish() }
        }
        StreamingPdfWriter(File(dir, "extra.pdf"), 1).use { writer ->
            writer.addPage(image, 16, 24, page)
            expectFailure { writer.addPage(image, 16, 24, page) }
            writer.finish()
            expectFailure { writer.finish() }
        }
    }

    @Test fun writerRejectsNonJpegAndWrongPageNumber() = withTemp { dir ->
        val badImage = File(dir, "bad.jpg").apply { writeText("not JPEG") }
        val layout = LayoutCalculator.calculate(16, 24, OutputSettings(pageNumbers = true))
        StreamingPdfWriter(File(dir, "bad.pdf"), 1).use { writer ->
            expectFailure { writer.addPage(badImage, 16, 24, layout) }
            expectFailure { writer.addPage(jpeg(dir), 16, 24, layout, 2) }
        }
    }

    private fun jpeg(dir: File): File {
        val bitmap = BufferedImage(16, 24, BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().let { graphics ->
            graphics.color = Color(30, 180, 140)
            graphics.fillRect(0, 0, 16, 24)
            graphics.dispose()
        }
        return File(dir, "page.jpg").also { check(ImageIO.write(bitmap, "JPEG", it)) }
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected an argument or state validation failure") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    private fun withTemp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("imagepdf-core-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
