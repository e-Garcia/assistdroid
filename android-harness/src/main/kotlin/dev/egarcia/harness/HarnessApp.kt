package ga.egarcia.harness

import android.app.Application
import ga.egarcia.harness.diag.CrashRecorder

class HarnessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
    }
}
