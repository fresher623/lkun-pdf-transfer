package cn.local.imagepdf.merge

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.ui.ResultUi
import cn.local.imagepdf.ui.TaskUi
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MergeStateStoreInstrumentedTest {
    @Test fun interruptedDraftRestoresOrderAndCleanupStaysInsideMergeDirectories(): Unit = withStore { context, store ->
        val source = File(context.filesDir, "merge-sources/input.pdf").apply { parentFile!!.mkdirs(); writeText("source fixture") }
        val resultDirectory = File(context.filesDir, "results/merge-${UUID.randomUUID()}").apply { mkdirs() }
        val result = File(resultDirectory, "result.pdf").apply { writeText("complete result fixture") }
        val mergePartial = File(resultDirectory, "merging.partial").apply { writeText("unfinished") }
        val scratch = File(resultDirectory, "scratch/arbitrary.tmp").apply { parentFile!!.mkdirs(); writeText("scratch") }
        val imagePartial = File(context.filesDir, "results/image-task/generating.partial").apply { parentFile!!.mkdirs(); writeText("image active") }
        val imageCache = File(context.cacheDir, "conversion/image-task.tmp").apply { parentFile!!.mkdirs(); writeText("image cache") }
        val mergeWork = File(context.cacheDir, "merge-work/task/arbitrary.tmp").apply { parentFile!!.mkdirs(); writeText("merge cache") }
        val importedScratch = File(context.cacheDir, "pdf-merge-import/task/arbitrary.tmp").apply { parentFile!!.mkdirs(); writeText("import cache") }
        val entry = MergeEntry("stable-id", "content://fixture/source", source.path, "input.pdf", 2, source.length())
        store.write(MergeState(entries = listOf(entry), outputName = "合并结果.pdf", task = TaskUi(total = 2),
            result = ResultUi(result.path, result.name, 2, result.length())))
        val restored = store.read()
        assertTrue(restored.interrupted)
        assertNull(restored.task)
        // Android may expose the same private file as /data/user/0 or /data/data.
        // The store intentionally restores canonical paths after its ownership check.
        assertEquals(listOf(entry.copy(sourcePath = source.canonicalPath)), restored.entries)
        assertEquals(result.canonicalPath, restored.result!!.filePath)
        store.cleanIncompleteFiles()
        assertFalse(mergePartial.exists())
        assertFalse(scratch.exists())
        assertFalse(mergeWork.exists())
        assertFalse(importedScratch.exists())
        assertTrue(source.isFile)
        assertTrue(result.isFile)
        assertTrue(imagePartial.isFile)
        assertTrue(imageCache.isFile)
    }

    @Test fun pathsOutsideOwnedMergeSourcesAndResultsAreRejected(): Unit = withStore { context, store ->
        val outside = File(context.cacheDir, "outside.pdf").apply { writeText("untouched") }
        val row = JSONObject().put("id", "id").put("originalUri", "content://fixture/input")
            .put("sourcePath", outside.path).put("name", "input.pdf").put("pages", 1).put("bytes", outside.length())
        val json = JSONObject().put("entries", JSONArray().put(row)).put("outputName", "output.pdf")
            .put("result", JSONObject().put("path", outside.path).put("name", "outside.pdf").put("pages", 1))
        File(context.filesDir, "merge-session.json").writeText(json.toString())
        val state = store.read()
        assertNull(state.result)
        assertEquals("", state.entries.single().sourcePath)
        assertNotNull(state.entries.single().issue)
        store.cleanIncompleteFiles()
        assertEquals("untouched", outside.readText())
    }

    private fun withStore(test: (Context, MergeStateStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "merge-store-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try { test(context, MergeStateStore(context)) } finally { root.deleteRecursively() }
    }
}
