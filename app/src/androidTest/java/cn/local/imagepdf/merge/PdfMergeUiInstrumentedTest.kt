package cn.local.imagepdf.merge

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.local.imagepdf.ImagePdfApplication
import cn.local.imagepdf.MainActivity
import cn.local.imagepdf.ui.ResultUi
import cn.local.imagepdf.ui.TaskUi
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mounts the production merge screen with a transient state and fake action receiver.
 * No real import, generation, export, runtime edit, provider grant, or external app is invoked.
 */
@RunWith(AndroidJUnit4::class)
class PdfMergeUiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var lastStage = "not started"

    private fun stage(value: String, remember: Boolean = true) {
        if (remember) lastStage = value
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Merge UI: $value\n") })
    }

    @Test fun minimumInputsAndHeavyTaskLocksAreVisibleAndDoNotDispatch(): Unit = runBlocking {
        withFixture(MergeState(entries = listOf(entry("a")))) { fixture ->
            stage("locks: one input")
            assertFalse(control("开始合并 · 1 页", expectedEnabled = false).isEnabled)
            assertEquals(0, onMain { fixture.generates })

            onMain { fixture.state = fixture.state.copy(entries = listOf(entry("a"), entry("b"))) }
            stage("locks: two inputs")
            assertTrue(control("开始合并 · 2 页", expectedEnabled = true).isEnabled)
            clickOwnText("开始合并 · 2 页")
            stage("locks: await generate callback after physical tap")
            awaitMain { fixture.generates == 1 }

            onMain { fixture.state = fixture.state.copy(otherTaskActive = true) }
            stage("locks: other tool busy")
            assertFalse(control("开始合并 · 2 页", expectedEnabled = false).isEnabled)
            assertFalse(control("＋ 添加 PDF", allowScroll = true, expectedEnabled = false).isEnabled)
            awaitOwnText("图片工具正在处理任务，暂时不能添加、合并或另存。当前草稿已保留，可继续查看。")
            assertEquals(1, onMain { fixture.generates })
            assertEquals(0, onMain { fixture.picks })

            // A complete prior result may be viewed while the other tool is busy, but cannot save.
            onMain {
                fixture.state = fixture.state.copy(result = ResultUi("unused-ui-result.pdf", "UI结果.pdf", 2, 100, exported = true))
            }
            stage("locks: existing result")
            assertFalse(control("再次另存为", allowScroll = true, expectedEnabled = false).isEnabled)
            assertEquals(0, onMain { fixture.saves })

            onMain { fixture.state = fixture.state.copy(otherTaskActive = false, task = TaskUi(stage = "正在合并", total = 2)) }
            stage("locks: running task cancellation")
            awaitOwnText("正在合并")
            assertFalse("A running task must not expose another start control", ownNodes().any { it.text?.toString()?.startsWith("开始合并 ·") == true })
            clickOwnText("取消任务", allowScroll = true)
            stage("locks: await cancellation callback")
            awaitMain { fixture.cancels == 1 }
            assertFalse(control("正在取消，请稍候…", expectedEnabled = false).isEnabled)
            assertEquals(1, onMain { fixture.generates })
            val beforeReturn = onMain { fixture.state.entries }
            stage("locks: return preserves draft")
            clickOwnText("返回图片工具")
            stage("locks: await return callback")
            awaitMain { fixture.returns == 1 }
            assertTrue("Returning must preserve the controlled draft", onMain { fixture.state.entries == beforeReturn })
            assertEquals(0, onMain { fixture.clears })
            assertEquals(0, onMain { fixture.newTasks })
        }
    }

    @Test fun orderRemoveAndClearDispatchStableIdsAndClearRequiresConfirmation(): Unit = runBlocking {
        withFixture(MergeState(entries = listOf(entry("a"), entry("b")))) { fixture ->
            // Select the first visible enabled control by screen position, not recycled node order.
            stage("order: move first document down")
            clickOwnText("下移 ↓", allowScroll = true)
            stage("order: await first physical move callback")
            awaitMain { fixture.moves.size == 1 }
            assertEquals(listOf("a" to "b"), onMain { fixture.moves.toList() })
            assertEquals(listOf("b", "a"), onMain { fixture.state.entries.map { it.id } })
            stage("order: await moved document at top")
            // A stable-key LazyColumn keeps its current item as the scroll anchor after a move.
            // Return to the list start before checking which document is first on screen.
            scrollToStart()
            awaitFirstVisibleDocument("文档_b.pdf")

            stage("order: move second document up")
            clickOwnText("↑ 上移", allowScroll = true)
            stage("order: await second physical move callback")
            awaitMain { fixture.moves.size == 2 }
            assertEquals(listOf("a" to "b", "a" to "b"), onMain { fixture.moves.toList() })
            assertEquals(listOf("a", "b"), onMain { fixture.state.entries.map { it.id } })

            stage("order: scroll to start and remove first document")
            scrollToStart()
            awaitFirstVisibleDocument("文档_a.pdf")
            clickOwnText("移除", allowScroll = true)
            stage("order: await physical removal callback")
            awaitMain { fixture.removed.isNotEmpty() }
            assertEquals(listOf("a"), onMain { fixture.removed.toList() })
            assertEquals(listOf("b"), onMain { fixture.state.entries.map { it.id } })

            stage("order: open clear confirmation and cancel")
            scrollToStart()
            clickOwnText("清空列表", allowScroll = true)
            awaitOwnText("清空合并列表？")
            assertEquals(0, onMain { fixture.clears })
            clickOwnText("取消", dialogTitle = "清空合并列表？")
            awaitNoOwnText("清空合并列表？")
            assertEquals(listOf("b"), onMain { fixture.state.entries.map { it.id } })

            stage("order: confirm clear")
            clickOwnText("清空列表", allowScroll = true)
            awaitOwnText("清空合并列表？")
            stage("order: tap dialog clear confirmation")
            clickOwnText("清空列表", dialogTitle = "清空合并列表？")
            stage("order: await clear callback")
            awaitMain { fixture.clears == 1 }
            awaitNoOwnText("清空合并列表？")
            assertTrue(onMain { fixture.state.entries.isEmpty() })
            stage("order: empty list disables merge")
            assertFalse(control("开始合并 · 0 页", expectedEnabled = false).isEnabled)
        }
    }

    @Test fun syntheticInputAndResultPreviewTurnPagesCloseResourcesAndRetainUnsavedResult(): Unit = runBlocking {
        withFixture(MergeState()) { fixture ->
            stage("preview: create controlled two-page PDF")
            val directory = File(context.cacheDir, "merge-ui-fixture-${UUID.randomUUID()}").apply { mkdirs() }
            val pdf = File(directory, "synthetic.pdf")
            try {
                createTwoPagePdf(pdf)
                val entries = listOf(entry("a").copy(sourcePath = pdf.path, pages = 2, bytes = pdf.length()), entry("b"))
                onMain { fixture.state = fixture.state.copy(entries = entries) }

                stage("preview: open input and turn page")
                val backgroundWindow = control("预览", allowScroll = true, expectedEnabled = true).windowId
                clickOwnText("预览", allowScroll = true)
                stage("preview: await input preview window")
                val inputWindow = awaitPreviewWindow("文档_a.pdf", backgroundWindow)
                stage("preview: await input rendered page 1")
                assertEquals(1, awaitRenderedPreviewPage(inputWindow))
                stage("preview: tap input next page")
                clickOwnText("下一页", expectedWindowId = inputWindow)
                stage("preview: await input rendered page 2")
                awaitOwnTextInWindow("PDF 第 2 页", inputWindow)
                stage("preview: close input preview")
                clickOwnText("关闭", expectedWindowId = inputWindow)
                stage("preview: await input window and rendered pages removed")
                awaitPreviewClosed(inputWindow, backgroundWindow)
                stage("preview: verify input descriptors closed")
                awaitFixtureClosed(pdf)

                val result = ResultUi(pdf.path, "合成预览结果.pdf", 2, pdf.length(), exported = false)
                onMain { fixture.state = fixture.state.copy(result = result) }
                stage("preview: await result card and stable preview control")
                awaitOwnText("合并完成")
                awaitOwnText("合成预览结果.pdf")
                awaitStableControl("预览结果", backgroundWindow)
                stage("preview: open result preview")
                clickOwnText("预览结果", allowScroll = true, expectedWindowId = backgroundWindow)
                stage("preview: await new result preview window")
                val resultWindow = awaitPreviewWindow("合成预览结果.pdf", backgroundWindow)
                assertTrue("Closing and reopening must create a new preview window", resultWindow != inputWindow)
                stage("preview: await result rendered initial page")
                val initialPage = awaitRenderedPreviewPage(resultWindow)
                stage("preview: result initial rendered page = $initialPage")
                assertEquals("A new preview should render its first page", 1, initialPage)
                stage("preview: tap result next page")
                clickOwnText("下一页", expectedWindowId = resultWindow)
                stage("preview: await result rendered page 2")
                awaitOwnTextInWindow("PDF 第 2 页", resultWindow)
                stage("preview: close result preview")
                clickOwnText("关闭", expectedWindowId = resultWindow)
                stage("preview: await result preview window removed")
                awaitPreviewClosed(resultWindow, backgroundWindow)
                stage("preview: verify result descriptors closed")
                awaitOwnText("合并完成")
                awaitFixtureClosed(pdf)

                stage("preview: cancel return confirmation")
                clickOwnText("返回图片工具")
                awaitOwnText("合并结果尚未另存")
                assertEquals(0, onMain { fixture.returns })
                clickOwnText("取消", dialogTitle = "合并结果尚未另存")
                awaitNoOwnText("合并结果尚未另存")
                assertTrue(onMain { fixture.state.result == result && fixture.state.entries == entries })
                stage("preview: confirm return retains result")
                clickOwnText("返回图片工具")
                awaitOwnText("合并结果尚未另存")
                clickOwnText("返回图片工具", dialogTitle = "合并结果尚未另存")
                stage("preview: await confirmed return callback")
                awaitMain { fixture.returns == 1 }
                assertTrue("Returning must retain the complete unsaved result", onMain { fixture.state.result == result })
                assertTrue("Returning must retain the input order", onMain { fixture.state.entries == entries })
            } catch (failure: Throwable) {
                runCatching { diagnoseSyntheticUi() }
                throw failure
            } finally {
                // Unmount the preview before deleting only this test's uniquely owned fixture.
                stage("preview: unmount fixture and release resources", remember = false)
                fixture.unmount()
                instrumentation.waitForIdleSync()
                awaitFixtureClosed(pdf)
                pdf.delete()
                directory.delete()
            }
        }
    }

    private fun entry(id: String) = MergeEntry(
        id = id, originalUri = "ui-fixture:$id", sourcePath = "", name = "文档_$id.pdf", pages = 1, bytes = 100,
    )

    private suspend fun withFixture(initial: MergeState, test: suspend (Fixture) -> Unit) {
        stage("fixture: await idle stores")
        assumeTrue("Mount UI fixtures only in an isolated application ID", context.packageName != "cn.local.imagepdf")
        val app = context.applicationContext as ImagePdfApplication
        val images = withTimeout(15_000) { app.runtime.state.first { !it.importing && !it.readingFolder } }
        val merging = withTimeout(15_000) { app.mergeRuntime.state.first { !it.importing } }
        assumeTrue("Do not interrupt either tool's active task", images.task == null && merging.task == null)
        val fixture = Fixture(initial)
        val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, MainActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            stage("fixture: launch activity")
            val mounted = ActivityScenario.launch<MainActivity>(launch)
            scenario = mounted
            fixture.unmount = { mounted.onActivity { activity -> activity.setContent {} } }
            stage("fixture: mount merge UI")
            mounted.onActivity { activity -> activity.setContent { PdfMergeScreen(fixture.state, fixture) } }
            instrumentation.waitForIdleSync()
            test(fixture)
            stage("fixture: checks complete")
        } catch (failure: Throwable) {
            runCatching { diagnoseSyntheticUi() }
            throw AssertionError("Merge UI failed at stage: $lastStage", failure)
        } finally {
            stage("fixture: close activity")
            scenario?.close()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = originalAccessibilityFlags }
            // Boolean-only guards never print a user's filenames, file paths or selected URIs.
            assertTrue("Existing image draft must remain unchanged", images.images == app.runtime.state.value.images)
            assertTrue("Existing image result must remain unchanged", images.result == app.runtime.state.value.result)
            assertTrue("Existing merge draft must remain unchanged", merging.entries == app.mergeRuntime.state.value.entries)
            assertTrue("Existing merge result must remain unchanged", merging.result == app.mergeRuntime.state.value.result)
            stage("fixture: original drafts and results preserved")
        }
    }

    private class Fixture(initial: MergeState) : MergeActions {
        var unmount: () -> Unit = {}
        var state by mutableStateOf(initial)
        var generates = 0
        var picks = 0
        var saves = 0
        var cancels = 0
        var returns = 0
        var clears = 0
        var newTasks = 0
        val moves = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()

        override fun pickPdfs() { picks++ }
        override fun move(fromId: String, toId: String) {
            moves += fromId to toId
            val entries = state.entries.toMutableList()
            val from = entries.indexOfFirst { it.id == fromId }
            val to = entries.indexOfFirst { it.id == toId }
            if (from >= 0 && to >= 0) entries.add(to, entries.removeAt(from))
            state = state.copy(entries = entries)
        }
        override fun remove(id: String) { removed += id; state = state.copy(entries = state.entries.filterNot { it.id == id }) }
        override fun clear() { clears++; state = state.copy(entries = emptyList()) }
        override fun setOutputName(name: String) { state = state.copy(outputName = name) }
        override fun generate() { generates++ }
        override fun cancel() { cancels++; state = state.copy(task = state.task?.copy(cancelling = true)) }
        override fun save() { saves++ }
        override fun share() = Unit
        override fun openExternal() = Unit
        override fun newTask() { newTasks++; state = MergeState() }
        override fun dismissError() { state = state.copy(error = null) }
        override fun dismissNotice() { state = state.copy(notice = null) }
        override fun confirmDuplicates(allow: Boolean) { state = state.copy(duplicateCount = 0) }
        override fun returnToImages() { returns++ }
    }

    /** Own-package BFS only; this never searches/clicks a system installer, permission prompt or file picker. */
    private fun ownNodes(): List<AccessibilityNodeInfo> {
        // Compose reuses virtual node IDs across recomposition and dialog windows. Cached
        // ACTION_CLICK success does not prove that the current screen received a gesture.
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        if (!root.refresh()) return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val found = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 2500) {
            val node = queue.removeFirst()
            val owner = node.packageName?.toString()
            if (owner != null && owner != context.packageName) continue
            if (node.isVisibleToUser) found += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return found.sortedWith(compareBy<AccessibilityNodeInfo> {
            Rect().also(it::getBoundsInScreen).top
        }.thenBy { Rect().also(it::getBoundsInScreen).left })
    }

    private fun matches(node: AccessibilityNodeInfo, text: String) =
        node.text?.toString() == text || node.contentDescription?.toString() == text

    private fun clickableParent(child: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = child
        repeat(8) {
            val candidate = node ?: return null
            val owner = candidate.packageName?.toString()
            if (owner != null && owner != context.packageName) return null
            if (candidate.isClickable) return candidate.takeIf { owner == context.packageName }
            node = candidate.parent
        }
        return null
    }

    private suspend fun control(text: String, allowScroll: Boolean = false, expectedEnabled: Boolean? = null): AccessibilityNodeInfo = withTimeout(15_000) {
        var lastScroll = 0L
        while (true) {
            ownNodes().filter { matches(it, text) }.mapNotNull(::clickableParent)
                .firstOrNull { expectedEnabled == null || it.isEnabled == expectedEnabled }?.let { return@withTimeout it }
            if (allowScroll && SystemClock.uptimeMillis() - lastScroll >= 500) { scrollOwnList(true); lastScroll = SystemClock.uptimeMillis() }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Control was not visible")
    }

    private data class TapTarget(val windowId: Int, val bounds: Rect)

    private fun visibleTapBounds(label: AccessibilityNodeInfo, button: AccessibilityNodeInfo): Rect? {
        val visible = Rect().also(button::getBoundsInScreen)
        if (!visible.intersect(Rect().also(label::getBoundsInScreen))) return null
        // getBoundsInScreen for the clickable parent can extend past the visible LazyColumn.
        // Intersect every scroll ancestor, just as the real menu navigation fixture does.
        var ancestor = label.parent
        var levels = 0
        while (levels++ < 24) {
            val parent = ancestor ?: break
            if (parent.packageName?.toString() != context.packageName) break
            if (parent.isScrollable && !visible.intersect(Rect().also(parent::getBoundsInScreen))) return null
            ancestor = parent.parent
        }
        // A few visible pixels of a label at the viewport edge are insufficient for a reliable
        // physical tap; scroll until a usable part of the real control is in view instead.
        val minimum = (12f * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        return visible.takeIf { it.width() >= minimum && it.height() >= minimum }
    }

    private fun tapTarget(text: String, dialogTitle: String?, expectedWindowId: Int? = null): TapTarget? {
        val nodes = ownNodes()
        val dialogWindow = if (dialogTitle != null) {
            nodes.firstOrNull { matches(it, dialogTitle) }?.windowId ?: return null
        } else null
        return nodes.asSequence().filter { matches(it, text) }
            .filter { dialogWindow == null || it.windowId == dialogWindow }
            .filter { expectedWindowId == null || it.windowId == expectedWindowId }
            .mapNotNull { label ->
                val button = clickableParent(label) ?: return@mapNotNull null
                if (!button.isEnabled || !button.isVisibleToUser || button.packageName?.toString() != context.packageName) return@mapNotNull null
                val visible = visibleTapBounds(label, button) ?: return@mapNotNull null
                TapTarget(button.windowId, visible)
            }.firstOrNull()
    }

    private fun scrollTowardControl(text: String, dialogTitle: String?, expectedWindowId: Int?): Boolean {
        val nodes = ownNodes()
        val dialogWindow = dialogTitle?.let { title -> nodes.firstOrNull { matches(it, title) }?.windowId }
        val label = nodes.firstOrNull {
            matches(it, text) && (dialogWindow == null || it.windowId == dialogWindow) &&
                (expectedWindowId == null || it.windowId == expectedWindowId) && clickableParent(it)?.isEnabled == true
        }
        if (label != null) {
            val labelBounds = Rect().also(label::getBoundsInScreen)
            var ancestor = label.parent
            repeat(24) {
                val parent = ancestor ?: return@repeat
                if (parent.packageName?.toString() != context.packageName) return@repeat
                if (parent.isScrollable) {
                    val viewport = Rect().also(parent::getBoundsInScreen)
                    val direction = if (labelBounds.top <= viewport.top) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    return parent.performAction(direction)
                }
                ancestor = parent.parent
            }
        }
        return scrollOwnList(true)
    }

    private suspend fun clickOwnText(text: String, allowScroll: Boolean = false, dialogTitle: String? = null, expectedWindowId: Int? = null): Unit = withTimeout(15_000) {
        var lastScroll = 0L
        var prior: TapTarget? = null
        var stableSince = 0L
        while (true) {
            instrumentation.waitForIdleSync()
            val target = tapTarget(text, dialogTitle, expectedWindowId)
            val now = SystemClock.uptimeMillis()
            if (target != null) {
                if (target != prior || stableSince == 0L) stableSince = now
                // A tap while a scroll is still settling can be consumed only to stop it.
                // Require the current own-window target to remain stationary for 600 ms.
                if (target == prior && now - stableSince >= 600L) {
                    val x = target.bounds.exactCenterX()
                    val y = target.bounds.exactCenterY()
                    stage("tap: $text; window=${target.windowId}; x=$x y=$y")
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try {
                        assertTrue("Own-app pointer down must be delivered", instrumentation.uiAutomation.injectInputEvent(down, true))
                        delay(80)
                    } finally {
                        down.recycle()
                        // Release the pointer even if the enclosing timeout cancels the short hold.
                        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                        try { assertTrue("Own-app pointer up must be delivered", instrumentation.uiAutomation.injectInputEvent(up, true)) }
                        finally { up.recycle() }
                    }
                    instrumentation.waitForIdleSync()
                    return@withTimeout
                }
            } else {
                stableSince = 0L
                if (allowScroll && now - lastScroll >= 500) {
                    scrollTowardControl(text, dialogTitle, expectedWindowId)
                    lastScroll = SystemClock.uptimeMillis()
                }
            }
            prior = target
            delay(100)
        }
    }

    private suspend fun awaitOwnText(text: String): Unit = withTimeout(15_000) {
        while (ownNodes().none { matches(it, text) }) delay(100)
    }

    private suspend fun awaitNoOwnText(text: String): Unit = withTimeout(15_000) {
        while (ownNodes().any { matches(it, text) }) delay(100)
    }

    private suspend fun awaitOwnTextInWindow(text: String, windowId: Int): Unit = withTimeout(15_000) {
        while (ownNodes().none { it.windowId == windowId && matches(it, text) }) delay(100)
    }

    private suspend fun awaitPreviewWindow(title: String, backgroundWindow: Int): Int = withTimeout(15_000) {
        while (true) {
            val nodes = ownNodes()
            val windowId = nodes.firstOrNull { matches(it, title) && it.windowId != backgroundWindow }?.windowId
            if (windowId != null && nodes.any { it.windowId == windowId && matches(it, "关闭") } &&
                instrumentation.uiAutomation.windows.any { it.id == windowId }) return@withTimeout windowId
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Preview window did not appear")
    }

    private suspend fun awaitRenderedPreviewPage(windowId: Int): Int = withTimeout(15_000) {
        while (true) {
            val nodes = ownNodes().filter { it.windowId == windowId }
            for (number in 1..2) {
                if (nodes.any { matches(it, "PDF 第 $number 页") } && nodes.any { matches(it, "$number / 2") }) return@withTimeout number
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Preview page did not render")
    }

    private suspend fun awaitPreviewClosed(previewWindow: Int, backgroundWindow: Int): Unit = withTimeout(15_000) {
        var stableSince = 0L
        while (true) {
            val nodes = ownNodes()
            val windows = instrumentation.uiAutomation.windows
            val closed = windows.none { it.id == previewWindow } &&
                nodes.any { it.windowId == backgroundWindow && matches(it, "PDF 合并") } &&
                nodes.none { it.windowId == previewWindow || matches(it, "PDF 第 1 页") || matches(it, "PDF 第 2 页") }
            if (!closed) stableSince = 0L
            else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - stableSince >= 600) return@withTimeout
            delay(100)
        }
    }

    private suspend fun awaitStableControl(text: String, windowId: Int): Unit = withTimeout(15_000) {
        var prior: TapTarget? = null
        var stableSince = 0L
        while (true) {
            val current = tapTarget(text, null, windowId)
            if (current == null || current != prior) stableSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - stableSince >= 600) return@withTimeout
            prior = current
            delay(100)
        }
    }

    /** Only fixed synthetic UI labels are emitted; private user names/URIs never enter logs. */
    private fun diagnoseSyntheticUi() {
        val allowed = setOf("PDF 合并", "文档_a.pdf", "文档_b.pdf", "合成预览结果.pdf", "合并完成",
            "预览", "预览结果", "关闭", "下一页", "上一页", "PDF 第 1 页", "PDF 第 2 页", "1 / 2", "2 / 2",
            "清空合并列表？", "合并结果尚未另存", "取消", "返回图片工具", "清空列表", "下移 ↓", "↑ 上移", "移除",
            "暂时无法渲染这一页，可尝试用其他应用打开 PDF。")
        val labels = ownNodes().mapNotNull { node ->
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).firstOrNull { it in allowed }
                ?: return@mapNotNull null
            val bounds = Rect().also(node::getBoundsInScreen)
            "$value [window=${node.windowId}; enabled=${node.isEnabled}; bounds=$bounds]"
        }.distinct().take(32)
        stage("synthetic UI diagnostics at $lastStage: ${labels.joinToString(" | ")}", remember = false)
    }

    private suspend fun awaitFirstVisibleDocument(name: String): Unit = withTimeout(15_000) {
        while (ownNodes().firstOrNull { node ->
                val text = node.text?.toString().orEmpty()
                text.startsWith("文档_") && text.endsWith(".pdf")
            }?.text?.toString() != name) delay(100)
    }

    private fun scrollOwnList(forward: Boolean): Boolean = ownNodes()
        .firstOrNull { it.isScrollable && it.packageName?.toString() == context.packageName }
        ?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true

    private suspend fun scrollToStart() {
        repeat(12) {
            if (!scrollOwnList(false)) return
            delay(100)
        }
    }

    private suspend fun awaitMain(predicate: () -> Boolean): Unit = withTimeout(15_000) {
        while (!onMain(predicate)) delay(50)
    }

    private suspend fun <T> onMain(action: () -> T): T = withContext(Dispatchers.Main) { action() }

    private fun createTwoPagePdf(file: File) {
        val document = PdfDocument()
        try {
            for (number in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(240, 320, number).create())
                try {
                    page.canvas.drawColor(if (number == 1) Color.rgb(224, 240, 235) else Color.rgb(224, 233, 250))
                    page.canvas.drawText("Synthetic page $number", 20f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f })
                } finally {
                    document.finishPage(page)
                }
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private suspend fun awaitFixtureClosed(file: File): Unit = withTimeout(15_000) {
        val ownedPath = file.canonicalPath
        while (true) {
            val descriptors = File("/proc/self/fd").listFiles()
            assertTrue("The test must be able to inspect its own file descriptors", descriptors != null)
            if (descriptors!!.none { descriptor ->
                    runCatching { Os.readlink(descriptor.path) == ownedPath }.getOrDefault(false)
                }) return@withTimeout
            delay(50)
        }
    }
}
