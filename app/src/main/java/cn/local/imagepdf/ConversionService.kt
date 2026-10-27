package cn.local.imagepdf

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cn.local.imagepdf.ui.TaskUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as ImagePdfApplication).runtime
    private var wakeLock: PowerManager.WakeLock? = null
    private var executing = false

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PDF 转换与保存", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示当前图片转换或文件保存进度"
            }
        )
        ServiceCompat.startForeground(this, NOTIFICATION, notification(runtime.state.value.task),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:conversion"
        ).apply { acquire(60 * 60 * 1000L) }
        scope.launch {
            runtime.state.map { it.task }.distinctUntilChanged().sample(500).collect { task ->
                if (task != null) runCatching { getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION, notification(task)) }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) runtime.cancel()
        else if (!executing) { executing = true; runtime.executePending(this) }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    // API 35 dataSync timeout: stop promptly, request cooperative cleanup, retain the draft.
    override fun onTimeout(startId: Int, fgsType: Int) {
        runtime.cancel()
        runtime.notice("系统已停止长时间后台任务，请回到应用重新生成。")
        finishWork()
    }
    internal fun finishWork() {
        executing = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (executing) runtime.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    private fun notification(task: TaskUi?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ConversionService::class.java)
            .setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = task?.let {
            if (it.exporting) "正在保存 · ${if (it.totalBytes > 0) it.copiedBytes * 100 / it.totalBytes else 0}%"
            else "${it.stage} · ${it.completed}/${it.total} 页"
        } ?: "正在准备任务"
        val progress = if (task?.exporting == true && task.totalBytes > 0)
            (task.copiedBytes * 100 / task.totalBytes).toInt() else task?.completed ?: 0
        val total = if (task?.exporting == true) 100 else task?.total ?: 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("l君のpdf转换")
            .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, progress, total == 0).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "取消任务", cancel).build()
    }
    companion object {
        private const val CHANNEL = "pdf_conversion"
        private const val NOTIFICATION = 1001
        private const val ACTION_CANCEL = "cn.local.imagepdf.CANCEL"
    }
}
