package dev.egarcia.harness

import android.app.Application
import dev.egarcia.harness.diag.CrashRecorder

class HarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
    }
}
