package cn.local.imagepdf

import android.app.Application

class ImagePdfApplication : Application() {
    lateinit var runtime: AppRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime(this)
    }
}
