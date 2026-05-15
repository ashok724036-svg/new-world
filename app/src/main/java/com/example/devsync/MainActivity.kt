package com.example.devsync

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, UserInterfaceActivity::class.java))
        startService(Intent(this, DeviceSyncService::class.java))
        finish()
    }
}
