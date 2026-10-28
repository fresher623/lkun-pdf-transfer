package cn.local.imagepdf

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.merge.MergeState
import cn.local.imagepdf.ui.AppScreen
import cn.local.imagepdf.ui.AppUiState
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Activity and both production tool screens. No fake composition, provider
 * grants, imports, draft edits, task creation or result removal are needed for navigation.
 */
@RunWith(AndroidJUnit4::class)
class ToolMenuInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ImagePdfApplication
    private var lastStage = "not started"

    @Test fun launcherMenuOpensBothToolsAndPreservesDrafts(): Unit = runBlocking {
        withRealActivity() { scenario, before ->
            stage("launcher: await the tool menu")
            awaitMenu()
            stage("launcher: open the image composition tool")
            tapOwnText(IMAGE_TITLE, allowScroll = true)
            awaitImageTool()
            before.assertPreserved()

            stage("launcher: recreate the image tool")
            scenario.recreate()
            awaitImageTool()
            before.assertPreserved()

            stage("launcher: use the image toolbar to return to menu")
            tapOwnText(RETURN_TO_MENU)
            awaitMenu()
            before.assertPreserved()

            stage("launcher: open PDF merge")
            tapOwnText(MERGE_TITLE, allowScroll = true)
            awaitMergeTool()
            before.assertPreserved()

            stage("launcher: recreate PDF merge")
            scenario.recreate()
            awaitMergeTool()
            before.assertPreserved()

            stage("launcher: use system back to return from merge")
            pressOwnBack()
            awaitMenu()
            before.assertPreserved()
        }
    }

    @Test fun foregroundNotificationToolIntentsAndRecreationPreserveSelectedTool(): Unit = runBlocking {
        // This is the same tool selector carried by a notification's Activity PendingIntent.
        // It never starts a foreground service or synthesizes a live conversion task.
        // ActivityScenario itself starts with a standard launcher Intent. The notification
        // Intent below is delivered to that real foreground Activity, not used to bootstrap
        // ActivityScenario; no automatic cold-notification coverage is claimed here.
        withRealActivity() { scenario, before ->
            stage("intent: standard launcher starts at the menu")
            awaitMenu()
            stage("intent: foreground merge notification opens the merge tool")
            sendForegroundIntent(ConversionService.TOOL_MERGE)
            awaitMergeTool()
            scenario.recreate()
            awaitMergeTool()

            stage("intent: a normal launcher intent keeps merge selected")
            sendForegroundIntent()
            awaitMergeTool()
            before.assertPreserved()

            stage("intent: image notification switches to the image tool")
            sendForegroundIntent(ConversionService.TOOL_IMAGE)
            awaitImageTool()
            scenario.recreate()
            awaitImageTool()

            stage("intent: a normal launcher intent keeps images selected")
            sendForegroundIntent()
            awaitImageTool()
            before.assertPreserved()

            stage("intent: system back returns from images to menu")
            pressOwnBack()
            awaitMenu()
            before.assertPreserved()

            stage("intent: a merge notification also routes from the menu")
            sendForegroundIntent(ConversionService.TOOL_MERGE)
            awaitMergeTool()
            stage("intent: merge toolbar returns to menu")
            tapOwnText(RETURN_TO_MENU)
            awaitMenu()
            scenario.recreate()
            awaitMenu()
            before.assertPreserved()
        }
    }

    private data class WorkSnapshot(val images: AppUiState, val merge: MergeState) {
        fun assertPreserved() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ImagePdfApplication
            val currentImages = app.runtime.state.value
            val currentMerge = app.mergeRuntime.state.value
            // Keep values inside boolean comparisons so a failure cannot log private names/URIs.
            assertTrue("Image draft and settings must survive navigation", images.images == currentImages.images &&
                images.settings == currentImages.settings)
            assertTrue("Image result must survive navigation", images.result == currentImages.result)
            assertTrue("Merge draft and output name must survive navigation", merge.entries == currentMerge.entries &&
                merge.outputName == currentMerge.outputName)
            assertTrue("Merge result must survive navigation", merge.result == currentMerge.result)
        }
    }

    private suspend fun withRealActivity(
        test: suspend (ActivityScenario<MainActivity>, WorkSnapshot) -> Unit,
    ) {
        assumeTrue("Navigation tests require an isolated benchmark application", context.packageName.endsWith(".benchmark"))
        withTimeout(15_000) { app.runtime.state.first { !it.importing && !it.readingFolder } }
        withTimeout(15_000) { app.mergeRuntime.state.first { !it.importing } }
        withTimeout(5_000) {
            while (app.taskGate.owner != null) {
                assumeTrue("Do not interrupt another task", app.runtime.state.value.task == null && app.mergeRuntime.state.value.task == null)
                delay(25)
            }
        }
        val images = app.runtime.state.value
        val merge = app.mergeRuntime.state.value
        assumeTrue("Preserve active tasks, selection dialogs and existing notices", images.task == null && merge.task == null &&
            images.folderSelection == null && images.pendingImportCount == 0 && merge.duplicateCount == 0 &&
            images.error == null && images.notice == null && merge.error == null && merge.notice == null)
        assumeTrue("Preserve existing screen choices and unsaved-result prompts", images.screen == AppScreen.HOME &&
            images.result?.exported != false && merge.result?.exported != false)
        val before = WorkSnapshot(images, merge)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            // CLEAR_TASK resets only this isolated Activity's navigation stack. It does not
            // clear app storage, runtime drafts, file grants or settings.
            val launch = foregroundIntent().setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            assertTrue("ActivityScenario must use the ordinary launcher request", launch.action == Intent.ACTION_MAIN &&
                launch.hasCategory(Intent.CATEGORY_LAUNCHER) && !launch.hasExtra(ConversionService.EXTRA_TOOL))
            stage("launch request: tool=none; main=true; flags=${launch.flags}", remember = false)
            val launched = ActivityScenario.launch<MainActivity>(launch)
            scenario = launched
            reportActivityState(launched, "after launch")
            stage("activity launched; production composition mounted")
            test(launched, before)
            stage("all route and preservation checks completed")
        } catch (failure: Throwable) {
            runCatching { diagnoseNavigation() }
            // Only at the end of a failed run, read the current save-state fields. Calling
            // Activity save-state machinery can mark lifecycle state as saved, so it must
            // never be inserted into successful routing/recreation assertion paths.
            scenario?.let { runCatching { reportActivityState(it, "failure", includeSavedState = true) } }
            throw AssertionError("Menu navigation failed at stage: $lastStage", failure)
        } finally {
            scenario?.close()
            before.assertPreserved()
        }
    }

    private fun foregroundIntent(tool: String? = null) = Intent()
        .setComponent(ComponentName(context.packageName, MainActivity::class.java.name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply {
            if (tool == null) { action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER) }
            // Match ConversionService's actual notification Activity PendingIntent: an
            // explicit component plus tool, without launcher action/category semantics.
            else putExtra(ConversionService.EXTRA_TOOL, tool)
        }

    private fun reportActivityState(scenario: ActivityScenario<MainActivity>, reason: String, includeSavedState: Boolean = false) {
        scenario.onActivity { activity ->
            val current = activity.intent
            val details = if (includeSavedState) {
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                val savedRoute = state.getString("toolDestination")?.takeIf { it in setOf("MENU", "IMAGES", "MERGE") } ?: "absent-or-other"
                "; currentSavedRoute=$savedRoute; legacyRouteKey=${state.containsKey("showingMerge") }"
            } else ""
            stage("activity $reason: tool=${knownTool(current.getStringExtra(ConversionService.EXTRA_TOOL))}; " +
                "hasTool=${current.hasExtra(ConversionService.EXTRA_TOOL)}; main=${current.action == Intent.ACTION_MAIN}; " +
                "flags=${current.flags}; task=${activity.taskId}; taskRoot=${activity.isTaskRoot}; " +
                "windowFocused=${activity.hasWindowFocus()}; finishing=${activity.isFinishing}; destroyed=${activity.isDestroyed}$details", remember = false)
        }
    }

    private fun knownTool(tool: String?) = when (tool) {
        null -> "none"
        ConversionService.TOOL_IMAGE -> "image"
        ConversionService.TOOL_MERGE -> "merge"
        else -> "other"
    }

    private suspend fun sendForegroundIntent(tool: String? = null) {
        withContext(Dispatchers.Main) { context.startActivity(foregroundIntent(tool)) }
        // Barrier: allow onNewIntent and the resulting Compose state update to execute before
        // checking that a normal launcher intent has preserved, rather than reset, the route.
        instrumentation.waitForIdleSync()
        delay(350)
    }

    private suspend fun awaitMenu() = awaitScreen(setOf(MENU_MARKER), setOf(RETURN_TO_MENU))
    private suspend fun awaitImageTool() = awaitScreen(setOf(IMAGE_TITLE, RETURN_TO_MENU), setOf(MENU_MARKER))
    private suspend fun awaitMergeTool() = awaitScreen(setOf(MERGE_TITLE, RETURN_TO_MENU), setOf(MENU_MARKER, IMAGE_TITLE))

    private suspend fun awaitScreen(required: Set<String>, absent: Set<String>): Unit = withTimeout(15_000) {
        var stableSince = 0L
        while (true) {
            val nodes = ownNodes()
            val ready = required.all { label -> nodes.any { matches(it, label) } } &&
                absent.none { label -> nodes.any { matches(it, label) } }
            if (!ready) stableSince = 0L
            else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - stableSince >= 400) return@withTimeout
            delay(100)
        }
    }

    /** Own-package nodes only: no installer, permission prompt or other app is searched/tapped. */
    private fun ownNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName || !root.refresh()) return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 2500) {
            val node = queue.removeFirst()
            val owner = node.packageName?.toString()
            if (owner != null && owner != context.packageName) continue
            if (node.isVisibleToUser) nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return nodes.sortedWith(compareBy<AccessibilityNodeInfo> { Rect().also(it::getBoundsInScreen).top }
            .thenBy { Rect().also(it::getBoundsInScreen).left })
    }

    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean =
        node.contentDescription?.toString() == label || node.text?.toString()?.lineSequence()?.any { it.trim() == label } == true

    private data class TapTarget(val window: Int, val bounds: Rect)

    private fun tapTarget(label: String): TapTarget? {
        for (node in ownNodes().filter { matches(it, label) }) {
            val labelBounds = Rect().also(node::getBoundsInScreen)
            var clickable: AccessibilityNodeInfo? = node
            repeat(8) {
                val current = clickable ?: return@repeat
                if (current.packageName?.toString() != context.packageName) return@repeat
                if (current.isClickable) {
                    val bounds = Rect().also(current::getBoundsInScreen)
                    if (!current.isEnabled || !current.isVisibleToUser || !bounds.intersect(labelBounds)) return@repeat
                    // A merged clickable card may have its title folded into the card's text.
                    // Clip to visible scroll ancestors instead of tapping the centre of a
                    // partially off-screen card below the actual viewport.
                    var ancestor = node.parent
                    var levels = 0
                    var visible = true
                    while (levels++ < 20) {
                        val parent = ancestor ?: break
                        if (parent.packageName?.toString() != context.packageName) break
                        if (parent.isScrollable && !bounds.intersect(Rect().also(parent::getBoundsInScreen))) {
                            visible = false
                            break
                        }
                        ancestor = parent.parent
                    }
                    if (visible && bounds.width() >= 2 && bounds.height() >= 2) return TapTarget(current.windowId, bounds)
                    return@repeat
                }
                clickable = current.parent
            }
        }
        return null
    }

    private suspend fun tapOwnText(label: String, allowScroll: Boolean = false): Unit = withTimeout(15_000) {
        var lastScroll = 0L
        while (true) {
            instrumentation.waitForIdleSync()
            val first = tapTarget(label)
            if (first != null) {
                delay(180)
                val stable = tapTarget(label)
                if (stable != null && stable == first) {
                    val downAt = SystemClock.uptimeMillis()
                    val x = stable.bounds.exactCenterX()
                    val y = stable.bounds.exactCenterY()
                    val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try {
                        assertTrue("Own-app touch down must be delivered", instrumentation.uiAutomation.injectInputEvent(down, true))
                        delay(80)
                    } finally {
                        down.recycle()
                        val up = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                        try { assertTrue("Own-app touch up must be delivered", instrumentation.uiAutomation.injectInputEvent(up, true)) }
                        finally { up.recycle() }
                    }
                    instrumentation.waitForIdleSync()
                    return@withTimeout
                }
            }
            if (allowScroll && SystemClock.uptimeMillis() - lastScroll >= 500) {
                ownNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastScroll = SystemClock.uptimeMillis()
            }
            delay(100)
        }
    }

    private fun pressOwnBack() {
        assertTrue("Back must be sent only while the own tool is visible", ownNodes().any { matches(it, RETURN_TO_MENU) })
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    private fun stage(value: String, remember: Boolean = true) {
        if (remember) lastStage = value
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Menu UI: $value\n") })
    }

    private fun diagnoseNavigation() {
        // The whitelist deliberately excludes arbitrary draft names, settings and URI values.
        val known = setOf(MENU_MARKER, IMAGE_TITLE, MERGE_TITLE, RETURN_TO_MENU, "PDF 尚未另存", "合并结果尚未另存")
        val visible = known.filter { label -> ownNodes().any { matches(it, label) } }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Menu UI: fixed labels visible at $lastStage: ${visible.joinToString()}\n") })
    }

    private companion object {
        const val MENU_MARKER = "选择一个工具，开始处理 PDF。"
        const val IMAGE_TITLE = "图片合成 PDF"
        const val MERGE_TITLE = "PDF 合并"
        const val RETURN_TO_MENU = "返回菜单"
    }
}
