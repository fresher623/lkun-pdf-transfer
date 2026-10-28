package cn.local.imagepdf

import android.app.Application

class ImagePdfApplication : Application() {
    val taskGate = TaskGate()
    val mergeRuntime by lazy { cn.local.imagepdf.merge.MergeRuntime(this, taskGate) }
    lateinit var runtime: AppRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime(this, taskGate)
        // Both stores finish recovery before their own tool permits new work.
        // Avoid a late first-open cleanup racing an already running merge.
        mergeRuntime
    }
}
