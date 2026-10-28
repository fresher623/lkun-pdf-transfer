package cn.local.imagepdf.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic ownership records and a captured release callback, never real user grants. */
@RunWith(AndroidJUnit4::class)
class ImageGrantIsolationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onlyNewImageGrantsCanBeReleasedAndTreesRemainUntouched(): Unit {
        val preferenceName = "grant-isolation-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        try {
            val expiredImage = Uri.parse("content://fixture.documents/document/expired-image")
            val keptImage = Uri.parse("content://fixture.documents/document/kept-image")
            val recentImage = Uri.parse("content://fixture.documents/document/recent-image")
            val unknownOtherTool = Uri.parse("content://fixture.documents/document/other-tool-pdf")
            val tree = DocumentsContract.buildTreeDocumentUri("fixture.documents", "shared-folder")
            val treeChild = DocumentsContract.buildDocumentUriUsingTree(tree, "shared-folder:image")
            val registry = ImageGrantRegistry(preferences)
            registry.recordNew(expiredImage, 10)
            registry.recordNew(keptImage, 10)
            registry.recordNew(recentImage, 300)
            registry.touchKnown(unknownOtherTool, 10)
            registry.recordNew(tree, 10)
            registry.recordNew(treeChild, 10)
            assertFalse("Pre-existing grants must not be adopted", preferences.contains(unknownOtherTool.toString()))
            assertFalse(preferences.contains(tree.toString()))
            assertFalse(preferences.contains(treeChild.toString()))
            // Even obsolete/corrupt tree bookkeeping must never cause a tree revocation.
            preferences.edit().putLong(tree.toString(), 10).putLong(treeChild.toString(), 10).commit()
            val released = mutableListOf<Uri>()
            ImageGrantRegistry(preferences).releaseUnused(setOf(keptImage.toString()), 200) { released += it }
            assertEquals(listOf(expiredImage), released)
            assertTrue(preferences.contains(keptImage.toString()))
            assertTrue(preferences.contains(recentImage.toString()))
        } finally { preferences.edit().clear().commit(); context.deleteSharedPreferences(preferenceName) }
    }

    @Test fun imageCacheCleanupCannotDeleteMergeSourcesOrFreshCopies(): Unit {
        val root = File(context.cacheDir, "source-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val sources = File(root, "sources").apply { mkdirs() }
            val merges = File(root, "merge-sources").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val old = now - 48L * 60 * 60 * 1000
            val expired = File(sources, "expired.source").apply { writeText("old image"); setLastModified(old) }
            val kept = File(sources, "active.source").apply { writeText("active image"); setLastModified(old) }
            val fresh = File(sources, "fresh.source").apply { writeText("new image") }
            val otherTool = File(merges, "retained.pdf").apply { writeText("merge input"); setLastModified(old) }
            ImageSourceFilesCleaner(sources).releaseUnused(setOf(kept.absolutePath), now - 24L * 60 * 60 * 1000)
            assertFalse(expired.exists())
            assertTrue(kept.isFile)
            assertTrue(fresh.isFile)
            assertEquals("merge input", otherTool.readText())
        } finally { root.deleteRecursively() }
    }
}
