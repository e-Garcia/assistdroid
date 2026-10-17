package ga.egarcia.harness.voice

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Transparent hop that shows Android's microphone permission dialog (a service or overlay cannot show it itself). */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Mic.permitted(this)) { finish(); return }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!Mic.permitted(this)) Toast.makeText(this, "Microphone permission is needed for voice input.", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Microphone allowed. Tap the microphone again.", Toast.LENGTH_SHORT).show()
        finish()
    }
}
