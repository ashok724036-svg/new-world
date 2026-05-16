package com.example.devsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start main sync service
            val syncIntent = Intent(context, DeviceSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(syncIntent)
            else
                context.startService(syncIntent)

            // Start recording service
            val recIntent = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(recIntent)
            else
                context.startService(recIntent)
        }
    }
}
