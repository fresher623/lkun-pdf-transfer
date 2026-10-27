package cn.local.imagepdf.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import cn.local.imagepdf.ImagePdfApplication
import cn.local.imagepdf.MainActivity
import cn.local.imagepdf.ui.FolderPicker
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses a real, URI-granted DocumentsProvider with only synthetic fixtures, never user pictures. */
@RunWith(AndroidJUnit4::class)
class FolderRepositoryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val testContext get() = instrumentation.context
    private val authority get() = folderTestAuthority(testContext)

    @Test fun listsAll500InOneFolderWithoutApplyingImportLimit(): Unit = runBlocking {
        withTree("five-hundred") { tree ->
            val result = FolderRepository(context).listImages(tree, remaining = 37)
            assertEquals("一次选择 500 张", result.folderName)
            assertEquals(37, result.remaining)
            assertEquals(500, result.images.size)
            assertEquals(500, result.images.map { it.uri }.toSet().size)
            assertEquals((1..500).map { "图片_$it.png" }, result.images.map { it.name })
            assertTrue(result.images.all { DocumentsContract.isDocumentUri(context, Uri.parse(it.uri)) })
            assertTrue(result.images.all { DocumentsContract.getTreeDocumentId(Uri.parse(it.uri)) == "five-hundred" })
        }
    }

    @Test fun currentFolderFiltersDirectoriesAndKeepsStableNaturalOrder(): Unit = runBlocking {
        withTree("mixed") { tree ->
            val result = FolderRepository(context).listImages(tree, remaining = 500)
            assertEquals("混合文件夹", result.folderName)
            assertEquals(7, result.images.size)
            assertEquals(listOf("1.jpg", "2.jpg", "2.jpg", "10.jpg"), result.images.take(4).map { it.name })
            assertEquals(listOf("mixed:two-first", "mixed:two-second"), result.images.filter { it.name == "2.jpg" }
                .map { DocumentsContract.getDocumentId(Uri.parse(it.uri)) })
            assertTrue(result.images.any { it.name == "photo.HEIC" })
            assertTrue("GIF is listed so import can report its unsupported animation explicitly", result.images.any { it.name == "动画.gif" })
            assertTrue("image MIME works without a recognized extension", result.images.any { it.name == "图像.bin" })
            assertFalse(result.images.any { it.name == "notes.txt" || it.name == "nested.jpg" })
        }
    }

    @Test fun tooManyEntriesFailExplicitlyAndEmptyFolderIsValid(): Unit = runBlocking {
        withTree("too-many") { tree ->
            try {
                FolderRepository(context).listImages(tree, remaining = 500)
                fail("More than 10000 entries must fail instead of silently truncating")
            } catch (failure: IOException) {
                assertTrue(failure.message.orEmpty().contains("10000"))
                assertTrue(failure.message.orEmpty().contains("没有导入"))
            }
        }
        withTree("empty") { tree ->
            val result = FolderRepository(context).listImages(tree, remaining = 0)
            assertEquals("空文件夹", result.folderName)
            assertTrue(result.images.isEmpty())
            assertEquals(0, result.remaining)
        }
    }

    @Test fun actualSelectAllButtonImports500FolderImages(): Unit = runBlocking {
        assumeTrue("UI fixture runs only in the isolated benchmark app", context.packageName.endsWith(".benchmark"))
        val runtime = (context.applicationContext as ImagePdfApplication).runtime
        withTimeout(10_000) { runtime.state.first { !it.importing } }
        val initial = runtime.state.value
        assumeTrue("Do not interrupt an active task or import", initial.task == null && !initial.importing)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "five-hundred")
        var scenario: ActivityScenario<MainActivity>? = null
        val copies = mutableListOf<File>()
        try {
            // Use a transient fixture grant so 500 new persistent grants cannot evict a user's
            // pre-existing document grants. ImportRepository must make controlled private copies.
            grantTree(tree, revoke = false, persistable = false)
            val selection = FolderRepository(context).listImages(tree, remaining = 500)
            val selected = CompletableDeferred<List<String>>()
            stage("opening the app-owned folder selection UI")
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, MainActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario = ActivityScenario.launch<MainActivity>(launch)
            stage("activity launched")
            scenario.onActivity { activity ->
                // Mount only the real folder picker, with a test callback. Never mutate the
                // existing AppRuntime draft/result while verifying these actual controls.
                activity.setContent {
                    MaterialTheme {
                        FolderPicker(selection, onImport = { selected.complete(it) },
                            onDismiss = { selected.complete(emptyList()) })
                    }
                }
            }
            stage("content mounted")
            clickOwnText("全选")
            stage("clicked actual 全选 button; waiting for import button to show 500")
            clickOwnText("导入已选 500 张")
            val chosen = withTimeout(15_000) { selected.await() }
            assertEquals(500, chosen.size)
            assertEquals(selection.images.map { it.uri }, chosen)
            var progress = 0
            val imported = withTimeout(90_000) {
                ImageRepository(context).importImages(chosen.map(Uri::parse), 0) { done, total ->
                    progress = done
                    assertEquals(500, total)
                }
            }
            copies += imported.mapNotNull { item ->
                Uri.parse(item.uri).takeIf { it.scheme == "file" }?.path?.let(::File)
            }
            assertEquals(500, progress)
            assertEquals(500, imported.size)
            assertEquals(500, imported.map { it.id }.toSet().size)
            assertTrue(imported.all { it.error == null })
            assertEquals((1..500).map { "图片_$it.png" }, imported.map { it.name })
            assertEquals((0L until 500L).toList(), imported.map { it.originalOrder })
            stage("actual 全选 and 导入 buttons imported all 500 fixture images")
        } finally {
            copies.filter { it.parentFile?.canonicalFile == File(context.filesDir, "sources").canonicalFile }
                .forEach { it.delete() }
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { grantTree(tree, revoke = true) }
            scenario?.close()
            assertTrue("Existing draft images must remain unchanged", initial.images == runtime.state.value.images)
            assertTrue("Existing generated result must remain unchanged", initial.result == runtime.state.value.result)
        }
    }

    private suspend fun clickOwnText(text: String): Unit = withTimeout(15_000) {
        while (true) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            // Never click a system dialog or another application if the user changes focus.
            if (root?.packageName?.toString() == context.packageName) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited++ < 2000) {
                    currentCoroutineContext().ensureActive()
                    val candidate = queue.removeFirst()
                    val candidatePackage = candidate.packageName?.toString()
                    if (candidatePackage != null && candidatePackage != context.packageName) continue
                    val exact = candidate.text?.toString() == text || candidate.contentDescription?.toString() == text
                    if (exact && candidate.isVisibleToUser) {
                        var node: AccessibilityNodeInfo? = candidate
                        var depth = 0
                        while (node != null && depth++ < 8) {
                            val nodePackage = node.packageName?.toString()
                            if (nodePackage != null && nodePackage != context.packageName) break
                            if (nodePackage == context.packageName && node.isClickable && node.isEnabled &&
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
                            node = node.parent
                        }
                    }
                    for (index in 0 until candidate.childCount) {
                        candidate.getChild(index)?.let { queue.addLast(it) }
                    }
                }
            }
            delay(100)
        }
    }

    private fun stage(message: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "Folder UI: $message\n")
    })

    private suspend fun withTree(id: String, action: suspend (Uri) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, id)
        grantTree(tree, revoke = false)
        try { action(tree) }
        finally {
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { grantTree(tree, revoke = true) }
        }
    }

    private fun grantTree(tree: Uri, revoke: Boolean, persistable: Boolean = true) {
        // Acquire a synchronous provider in the fixture owner's UID. This avoids OEM background
        // broadcast policies while keeping actual DocumentsProvider access protected by URI grants.
        val grantAuthority = testContext.packageManager
            .getPackageInfo(testContext.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
            .first { it.name == FolderTestGrantProvider::class.java.name }.authority
        val operation = if (revoke) "revoke" else "grant"
        stage("$operation tree ${DocumentsContract.getTreeDocumentId(tree)} using synchronous fixture provider")
        val result = context.contentResolver.call(Uri.parse("content://$grantAuthority"), operation,
            tree.toString(), Bundle().apply {
                putString("target", context.packageName)
                putBoolean("persistable", persistable)
            })
        val detail = result?.getString("detail") ?: "no acknowledgement"
        stage("fixture provider success=${result?.getBoolean("success", false)} detail=$detail")
        assertTrue("Test folder URI grant failed: $detail", result?.getBoolean("success", false) == true)
    }
}

private fun folderTestAuthority(context: Context): String = context.packageManager
    .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
    .first { it.name == FolderTestDocumentsProvider::class.java.name }.authority
