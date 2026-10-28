package cn.local.imagepdf.merge

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.BuildConfig
import cn.local.imagepdf.ImagePdfApplication
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfMergeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun awaitStartup(): Unit = runBlocking {
        val app = context.applicationContext as ImagePdfApplication
        withTimeout(15_000) {
            app.runtime.state.first { !it.importing }
            app.mergeRuntime.state.first { !it.importing }
            while (app.taskGate.owner != null) delay(20)
        }
        instrumentation.waitForIdleSync()
        PDFBoxResourceLoader.init(context)
    }

    @Test fun textVectorsPageBoxesAndRotationSurviveObjectMerge(): Unit = runBlocking {
        val fixture = fixtureFolder()
        val imported = mutableListOf<MergeEntry>()
        var output: File? = null
        try {
            val first = createPdf(File(fixture, "相同名称_1.pdf"), 1, 2, images = false)
            val second = createPdf(File(fixture, "相同名称_2.pdf"), 2, 1, images = false)
            val grantsBefore = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
            val repository = PdfMergeRepository(context)
            imported += repository.importUris(listOf(uri(first)), emptyList())
            imported += repository.importUris(listOf(uri(second)), imported)
            assertTrue(imported.all { it.issue == null })
            assertEquals(listOf(2, 1), imported.map { it.pages })
            assertEquals(grantsBefore, context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet())
            assertTrue(imported.all { File(it.sourcePath).parentFile?.name == "merge-sources" })
            val progress = mutableListOf<Int>()
            val result = repository.merge(taskId(), imported.reversed(), "含文字矢量_../合并.pdf") { completed, _, _ -> progress += completed }
            output = File(result.filePath)
            assertEquals(3, result.pageCount)
            assertFalse(result.exported)
            assertFalse(output!!.name.contains('/'))
            assertEquals(3, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            val evidence = syntheticEvidence("three-pages")
            first.copyTo(File(evidence, "source-001.pdf"))
            second.copyTo(File(evidence, "source-002.pdf"))
            output!!.copyTo(File(evidence, "merged.pdf"))
            PDDocument.load(output).use { merged ->
                val text = PDFTextStripper().getText(merged)
                File(evidence, "merged-text.txt").writeText(text)
                listOf(first, second).forEachIndexed { index, source ->
                    PDDocument.load(source).use { original ->
                        File(evidence, "source-${index + 1}-text.txt").writeText(PDFTextStripper().getText(original))
                        assertEquals((1..original.numberOfPages).map { label(index + 1, it) }, extractedPageLabels(original))
                    }
                }
                val labels = listOf(label(2, 1), label(1, 1), label(1, 2))
                assertEquals("Selectable labels must follow the exact output page order; synthetic evidence=${evidence.path}",
                    labels, extractedPageLabels(merged))
                labels.forEachIndexed { index, _ ->
                    val page = merged.getPage(index)
                    assertTrue("Text must remain a font resource", page.resources.fontNames.iterator().hasNext())
                    assertFalse("Pure vector sources must not be converted to page images", page.resources.xObjectNames.iterator().hasNext())
                    val commands = page.contents.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
                    assertTrue("Vector rectangle operators must survive", commands.contains(" re") || commands.contains("re\n"))
                    assertTrue(commands.contains("Tj"))
                }
                PDDocument.load(second).use {
                    assertPageBoxesEqual(it.getPage(0), merged.getPage(0))
                    assertPageContentsEqual(it.getPage(0), merged.getPage(0))
                }
                PDDocument.load(first).use {
                    assertPageBoxesEqual(it.getPage(0), merged.getPage(1))
                    assertPageBoxesEqual(it.getPage(1), merged.getPage(2))
                    assertPageContentsEqual(it.getPage(0), merged.getPage(1))
                    assertPageContentsEqual(it.getPage(1), merged.getPage(2))
                }
            }
        } finally { cleanup(imported, output, fixture) }
    }

    @Test fun complexEncryptedCorruptAndImageInputsAreExplicitlyRejected(): Unit = runBlocking {
        val fixture = fixtureFolder()
        val imported = mutableListOf<MergeEntry>()
        try {
            val cases = listOf("AcroForm", "XFA", "Perms", "Names", "Collection", "Outlines", "StructTreeRoot", "Annots", "OpenAction")
            val files = cases.map { feature ->
                val file = createPdf(File(fixture, "$feature.pdf"), 1, 1)
                val modified = File(fixture, "complex-$feature.pdf")
                PDDocument.load(file).use { doc ->
                    val value = COSDictionary()
                    when (feature) {
                        "Annots" -> doc.getPage(0).cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(COSDictionary().apply {
                            setItem(COSName.SUBTYPE, COSName.getPDFName("Link"))
                        }) })
                        "XFA" -> doc.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, COSDictionary().apply {
                            setItem(COSName.getPDFName("XFA"), COSString("<xfa></xfa>"))
                        })
                        "OpenAction" -> doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, value.apply {
                            setItem(COSName.S, COSName.getPDFName("JavaScript")); setItem(COSName.getPDFName("JS"), COSString("throw new Error('must never execute');"))
                        })
                        "Perms" -> doc.documentCatalog.cosObject.setItem(COSName.getPDFName(feature), value.apply {
                            setItem(COSName.getPDFName("DocMDP"), COSDictionary().apply {
                                setItem(COSName.TYPE, COSName.getPDFName("Sig")); setItem(COSName.getPDFName("ByteRange"), COSArray())
                            })
                        })
                        else -> doc.documentCatalog.cosObject.setItem(COSName.getPDFName(feature), value)
                    }
                    doc.save(modified)
                }
                modified
            }.toMutableList()
            files += File(fixture, "真实密码保护.pdf").apply { writeBytes(Base64.decode(ENCRYPTED_PDF, Base64.DEFAULT)) }
            files += File(fixture, "截断.pdf").apply { writeBytes(createPdf(File(fixture, "完整.pdf"), 1, 1).readBytes().dropLast(30).toByteArray()) }
            files += File(fixture, "实际是图片.pdf").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) }
            val repository = PdfMergeRepository(context)
            imported += repository.importUris(files.map(::uri), emptyList())
            assertEquals(files.size, imported.size)
            imported.forEach { item ->
                assertNotNull("Complex input must have an explicit issue: ${item.name}", item.issue)
                assertTrue(item.sourcePath.isBlank())
            }
            assertTrue(imported[cases.size].issue.orEmpty().contains("加密"))
            val bad = imported.take(2)
            assertTrue(expectFailure { repository.merge(taskId(), bad, "不可生成.pdf") }.message.orEmpty().contains("异常"))
        } finally { cleanup(imported, null, fixture) }
    }

    @Test fun cancellationAndChangedSourcesDoNotLeavePartialOrScratch(): Unit = runBlocking {
        val fixture = fixtureFolder()
        val imported = mutableListOf<MergeEntry>()
        val repository = PdfMergeRepository(context)
        val cancelledTask = taskId()
        val changedTask = taskId()
        try {
            val first = createPdf(File(fixture, "first.pdf"), 1, 3)
            val second = createPdf(File(fixture, "second.pdf"), 2, 3)
            imported += repository.importUris(listOf(uri(first), uri(second)), emptyList())
            assertTrue(imported.all { it.issue == null })
            try {
                repository.merge(cancelledTask, imported, "cancel.pdf") { completed, _, _ -> if (completed == 1) throw CancellationException("fixture cancel") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertNoIncomplete(cancelledTask)
            val changed = File(imported[0].sourcePath)
            changed.appendText("broken-tail")
            val failure = expectFailure { repository.merge(changedTask, imported, "changed.pdf") }
            assertNotNull(failure.message)
            assertNoIncomplete(changedTask)
        } finally {
            cleanup(imported, null, fixture)
            controlledResultDirectory(cancelledTask).deleteRecursively()
            controlledResultDirectory(changedTask).deleteRecursively()
        }
    }

    @Test fun countPageByteAndPathBudgetsAreEnforced(): Unit = runBlocking {
        val fixture = fixtureFolder()
        val imported = mutableListOf<MergeEntry>()
        val repository = PdfMergeRepository(context)
        try {
            val source = createPdf(File(fixture, "small.pdf"), 1, 1)
            assertTrue(expectFailure { repository.importUris(List(51) { uri(source) }, emptyList()) }.message.orEmpty().contains("50"))
            // ftruncate makes a sparse metadata fixture; no 256-MiB payload is generated or read.
            val oversized = File(fixture, "sparse-over-limit.pdf")
            RandomAccessFile(oversized, "rw").use { it.setLength(PdfMergeLimits.MAX_FILE_BYTES + 1) }
            val tooLarge = repository.importUris(listOf(uri(oversized)), emptyList()).single()
            assertTrue(tooLarge.issue.orEmpty().contains("256"))
            val metadataOnly = MergeEntry(UUID.randomUUID().toString(), "", "", "预算占位", 1000, 0)
            val tooManyPages = repository.importUris(listOf(uri(source)), listOf(metadataOnly)).single()
            assertTrue(tooManyPages.issue.orEmpty().contains("1000"))
            val fullBytes = metadataOnly.copy(pages = 1, bytes = PdfMergeLimits.MAX_TOTAL_BYTES)
            val tooManyBytes = repository.importUris(listOf(uri(source)), listOf(fullBytes)).single()
            assertTrue(tooManyBytes.issue.orEmpty().contains("512"))
            imported += repository.importUris(listOf(uri(source), uri(source)), emptyList())
            assertTrue(imported.all { it.issue == null })
            assertNotEquals(imported[0].id, imported[1].id)
            assertEquals(imported[0].originalUri, imported[1].originalUri)
            assertTrue(expectFailure { repository.merge("merge-../../outside", imported, "path.pdf") }.message.orEmpty().contains("标识"))
            assertTrue(expectFailure { repository.merge(taskId(), imported.take(1), "single.pdf") }.message.orEmpty().contains("2～50"))
            val foreign = imported[0].copy(sourcePath = source.path)
            assertTrue(expectFailure { repository.merge(taskId(), listOf(foreign, imported[1]), "foreign.pdf") }.message.orEmpty().contains("副本"))
        } finally { cleanup(imported, null, fixture) }
    }

    @Test fun mergeCleanupNeverTouchesImageSourcesOrExistingResults(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val imageSource = File(context.filesDir, "sources/$token.source").apply { parentFile!!.mkdirs(); writeText("image sentinel") }
        val retainedResult = File(context.filesDir, "results/sentinel-$token.pdf").apply { parentFile!!.mkdirs(); writeText("completed sentinel") }
        val mergeRoot = File(context.filesDir, "merge-sources").apply { mkdirs() }
        val existing = mergeRoot.listFiles().orEmpty().filter { it.extension == "pdf" }.map {
            MergeEntry(it.nameWithoutExtension, "", it.path, it.name, 1, it.length())
        }
        val stale = File(mergeRoot, "$token.pdf").apply { writeText("stale merge copy"); setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000) }
        try {
            PdfMergeRepository(context).releaseUnusedSources(existing)
            assertFalse(stale.exists())
            assertEquals("image sentinel", imageSource.readText())
            assertEquals("completed sentinel", retainedResult.readText())
        } finally { imageSource.delete(); retainedResult.delete(); stale.delete() }
    }

    /** Opt-in real boundary run: -e mergeBoundary true -e class ...PdfMergeInstrumentedTest#fiftyFilesAndThousandPagesEvidence */
    @LargeTest
    @Test fun fiftyFilesAndThousandPagesEvidence(): Unit = runBlocking {
        assumeTrue("Add -e mergeBoundary true for the 50-file/1000-page synthetic boundary", InstrumentationRegistry.getArguments().getString("mergeBoundary") == "true")
        val fixture = fixtureFolder()
        val repository = PdfMergeRepository(context)
        val imported = mutableListOf<MergeEntry>()
        var output: File? = null
        val evidence = File(context.getExternalFilesDir(null), "benchmark-evidence/pdf-merge/${System.currentTimeMillis()}").apply { mkdirs() }
        val stats = File(evidence, "memory.csv")
        val started = SystemClock.elapsedRealtime()
        var lastMeasured = -1
        stats.writeText("phase,page,elapsed_ms,java_bytes,native_bytes,pss_kb,rss_kb,fd_count\n")
        fun sample(phase: String, page: Int) {
            val runtime = Runtime.getRuntime()
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val rss = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull() ?: -1 }
            stats.appendText("$phase,$page,${SystemClock.elapsedRealtime() - started},${runtime.totalMemory() - runtime.freeMemory()},${Debug.getNativeHeapAllocatedSize()},${memory.totalPss},$rss,${File("/proc/self/fd").list()?.size ?: -1}\n")
        }
        try {
            val files = (1..50).map { index -> createPdf(File(fixture, "source-${index.toString().padStart(3, '0')}.pdf"), index, 20, images = true) }
            sample("before_import", 0)
            val importedAt = SystemClock.elapsedRealtime()
            imported += repository.importUris(files.map(::uri), emptyList())
            val importMillis = SystemClock.elapsedRealtime() - importedAt
            assertEquals(50, imported.size)
            assertTrue(imported.all { it.issue == null })
            assertEquals(1000, imported.sumOf { it.pages })
            sample("after_import", 0)
            val mergeAt = SystemClock.elapsedRealtime()
            val result = repository.merge(taskId(), imported, "合并边界_1000页.pdf") { done, _, _ ->
                if (done % 100 == 0 && done != lastMeasured) {
                    sample("merge", done); lastMeasured = done
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PDF merge boundary: $done/1000\n") })
                }
            }
            val mergeMillis = SystemClock.elapsedRealtime() - mergeAt
            output = File(result.filePath)
            assertEquals(1000, result.pageCount)
            sample("complete", 1000)
            // Mirror only these controlled fixtures before content assertions so a failed
            // run can be inspected by an independent reader without copying user documents.
            val inputEvidence = File(evidence, "inputs").apply { mkdirs() }
            files.forEach { it.copyTo(File(inputEvidence, it.name), overwrite = true) }
            output!!.copyTo(File(evidence, "merged.pdf"), overwrite = true)
            val expected = File(evidence, "expected-pages.csv")
            expected.writeText("page,label,media_llx,media_lly,media_width,media_height,crop_llx,crop_lly,crop_width,crop_height,rotation\n")
            var pageIndex = 0
            files.forEach { file -> PDDocument.load(file).use { doc -> doc.pages.forEach { page ->
                pageIndex++
                expected.appendText("$pageIndex,${label((pageIndex - 1) / 20 + 1, (pageIndex - 1) % 20 + 1)},${page.mediaBox.lowerLeftX},${page.mediaBox.lowerLeftY},${page.mediaBox.width},${page.mediaBox.height},${page.cropBox.lowerLeftX},${page.cropBox.lowerLeftY},${page.cropBox.width},${page.cropBox.height},${page.rotation}\n")
            } } }
            PDDocument.load(output).use { merged ->
                val text = PDFTextStripper().getText(merged)
                File(evidence, "merged-text.txt").writeText(text)
                val expectedLabels = (1..50).flatMap { document -> (1..20).map { page -> label(document, page) } }
                assertEquals("Every unique selectable label must stay on its exact output page; synthetic evidence=${evidence.path}",
                    expectedLabels, extractedPageLabels(merged))
                var offset = 0
                files.forEachIndexed { documentIndex, file -> PDDocument.load(file).use { original ->
                    assertEquals((1..20).map { label(documentIndex + 1, it) }, extractedPageLabels(original))
                    original.pages.forEach { page ->
                        assertPageBoxesEqual(page, merged.getPage(offset))
                        assertPageContentsEqual(page, merged.getPage(offset))
                        offset++
                    }
                } }
                assertEquals(1000, offset)
            }
            val deviceMemory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            File(evidence, "summary.txt").writeText("SYNTHETIC fixtures only; no user PDFs\nfiles=50\npages=1000\nimport_ms=$importMillis\nmerge_ms=$mergeMillis\ninput_bytes=${imported.sumOf { it.bytes }}\noutput_bytes=${result.bytes}\nmodel=${Build.MANUFACTURER} ${Build.MODEL}\nandroid=${Build.VERSION.SDK_INT}\nandroid_release=${Build.VERSION.RELEASE}\nram_bytes=${deviceMemory.totalMem}\nheap_limit_bytes=${Runtime.getRuntime().maxMemory()}\nbuild_type=${BuildConfig.BUILD_TYPE}\ndebuggable=${BuildConfig.DEBUG}\nversion=${BuildConfig.VERSION_NAME}\nThis single-device sample does not certify 4-GB RAM devices or arbitrary 256-MiB PDFs.\n")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PDF merge evidence: ${evidence.absolutePath}; merge=${mergeMillis}ms\n") })
        } finally { cleanup(imported, output, fixture) }
    }

    private fun createPdf(file: File, documentIndex: Int, count: Int, images: Boolean = false): File {
        PDDocument().use { document ->
            val image = if (images) {
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(documentIndex * 23 % 255, 80, 130)) }
                try { JPEGFactory.createFromImage(document, bitmap) } finally { bitmap.recycle() }
            } else null
            for (number in 1..count) {
                val width = 300f + documentIndex % 5 * 20 + number % 3 * 10
                val height = 500f + documentIndex % 4 * 15
                val page = PDPage(PDRectangle(width, height)).apply {
                    cropBox = PDRectangle(5f, 10f, width - 10, height - 20)
                    trimBox = PDRectangle(8f, 12f, width - 16, height - 24)
                    artBox = PDRectangle(10f, 15f, width - 20, height - 30)
                    rotation = (documentIndex + number) % 4 * 90
                }
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 14f)
                    content.newLineAtOffset(30f, height - 40); content.showText(label(documentIndex, number)); content.endText()
                    content.setNonStrokingColor(0.2f, 0.6f, 0.4f); content.addRect(40f, 50f, 80f, 30f); content.fill()
                    if (image != null) content.drawImage(image, 150f, 50f, 40f, 30f)
                }
            }
            document.save(file)
        }
        return file
    }

    private fun label(document: Int, page: Int) = "DOC${document.toString().padStart(3, '0')}_PAGE${page.toString().padStart(3, '0')}"
    private fun extractedPageLabels(document: PDDocument): List<String> {
        // PdfBox-Android inserts visual line breaks between glyph groups on 90/270-degree
        // pages, equally in our source and merged PDFs. Keep explicit page boundaries and
        // remove extraction whitespace only for these single-label ASCII fixtures; do not
        // change the PDF's page rotation or content. Raw extraction is retained as evidence.
        val begin = "\u001e"
        val end = "\u001f"
        val extracted = PDFTextStripper().apply { pageStart = begin; pageEnd = end }.getText(document)
        val pages = extracted.split(begin).drop(1)
        assertEquals("Text extraction must retain every individual page boundary", document.numberOfPages, pages.size)
        return pages.map { page ->
            assertTrue("Missing page-end boundary", page.contains(end))
            page.substringBefore(end).filterNot(Char::isWhitespace)
        }
    }
    private fun syntheticEvidence(name: String) = File(context.getExternalFilesDir(null),
        "benchmark-evidence/pdf-merge/$name-${System.currentTimeMillis()}").apply { mkdirs() }
    private fun fixtureFolder() = File(context.filesDir, "results/merge-fixtures-${UUID.randomUUID()}").apply { mkdirs() }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun taskId() = "merge-${UUID.randomUUID()}"
    private fun controlledResultDirectory(task: String) = File(context.filesDir, "results/$task")

    private suspend fun expectFailure(block: suspend () -> Unit): PdfMergeException {
        try { block(); fail("Expected explicit PDF rejection") }
        catch (failure: PdfMergeException) { return failure }
        throw AssertionError("No failure")
    }

    private fun assertNoIncomplete(task: String) {
        val directory = controlledResultDirectory(task)
        assertFalse(File(directory, "merging.partial").exists())
        assertFalse(File(directory, "scratch").exists())
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "pdf" })
    }

    private fun assertPageBoxesEqual(expected: PDPage, actual: PDPage) {
        assertEquals(expected.rotation, actual.rotation)
        listOf(expected.mediaBox to actual.mediaBox, expected.cropBox to actual.cropBox,
            expected.bleedBox to actual.bleedBox, expected.trimBox to actual.trimBox,
            expected.artBox to actual.artBox).forEach { (first, second) ->
            assertEquals(first.lowerLeftX, second.lowerLeftX, 0.01f); assertEquals(first.lowerLeftY, second.lowerLeftY, 0.01f)
            assertEquals(first.width, second.width, 0.01f); assertEquals(first.height, second.height, 0.01f)
        }
    }

    private fun assertPageContentsEqual(expected: PDPage, actual: PDPage) {
        val sourceBytes = expected.contents.use { it.readBytes() }
        val outputBytes = actual.contents.use { it.readBytes() }
        assertArrayEquals("Decoded page content operators must remain byte-identical", sourceBytes, outputBytes)
    }

    private fun cleanup(entries: List<MergeEntry>, output: File?, fixtures: File) {
        entries.forEach { entry -> File(entry.sourcePath).takeIf {
            entry.sourcePath.isNotBlank() && it.parentFile?.canonicalFile == File(context.filesDir, "merge-sources").canonicalFile
        }?.delete() }
        output?.parentFile?.takeIf { it.parentFile?.canonicalFile == File(context.filesDir, "results").canonicalFile }?.deleteRecursively()
        fixtures.takeIf { it.parentFile?.canonicalFile == File(context.filesDir, "results").canonicalFile }?.deleteRecursively()
    }

    companion object {
        // Generated with pypdf: one real RC4-128 password-protected page, password merge-test-password.
        // Fixture only: no production encryption/signature API or Bouncy Castle dependency is needed.
        private const val ENCRYPTED_PDF = "JVBERi0xLjMKJeLjz9MKMSAwIG9iago8PAovUHJvZHVjZXIgPDAxMmVmYzA3ZmQ+Cj4+CmVuZG9iagoyIDAgb2JqCjw8Ci9UeXBlIC9QYWdlcwovQ291bnQgMQovS2lkcyBbIDQgMCBSIF0KPj4KZW5kb2JqCjMgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1BhZ2VzIDIgMCBSCj4+CmVuZG9iago0IDAgb2JqCjw8Ci9UeXBlIC9QYWdlCi9SZXNvdXJjZXMgPDwKPj4KL01lZGlhQm94IFsgMC4wIDAuMCAzMDAgNDAwIF0KL1BhcmVudCAyIDAgUgo+PgplbmRvYmoKNSAwIG9iago8PAovViAyCi9SIDMKL0xlbmd0aCAxMjgKL1AgNDI5NDk2NzI5MgovRmlsdGVyIC9TdGFuZGFyZAovTyA8NDgwMjBkYjJlYjc3NDFlOWJhN2Y4NjU1ODAxZWU4MzM1YWY0ZDljZGU3Y2YwOTdiM2U4MjU3Y2ViZTAyZWU3MD4KL1UgPDRjMWRhYzlhY2M3ZDlkZmYxYjRlZWNjNzI3ZTk2NTA3MjhiZjRlNWU0ZTc1OGE0MTY0MDA0ZTU2ZmZmYTAxMDg+Cj4+CmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTUgMDAwMDAgbiAKMDAwMDAwMDA1OSAwMDAwMCBuIAowMDAwMDAwMTE4IDAwMDAwIG4gCjAwMDAwMDAxNjcgMDAwMDAgbiAKMDAwMDAwMDI2MSAwMDAwMCBuIAp0cmFpbGVyCjw8Ci9TaXplIDYKL1Jvb3QgMyAwIFIKL0luZm8gMSAwIFIKL0lEIFsgPDM2MzczMjY0MzYzODM0MzczMTM4NjM2MjMwMzE2NDMzMzgzNTYyNjUzNjMyNjYzMTMwNjIzMjMwMzY2NjM3Mzk+IDwzNjM3MzI2NDM2MzgzNDM3MzEzODYzNjIzMDMxNjQzMzM4MzU2MjY1MzYzMjY2MzEzMDYyMzIzMDM2NjYzNzM5PiBdCi9FbmNyeXB0IDUgMCBSCj4+CnN0YXJ0eHJlZgo0NzYKJSVFT0YK"
    }
}
