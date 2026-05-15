package com.example.devsync

  import android.os.Bundle
  import android.widget.*
  import androidx.appcompat.app.AppCompatActivity
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import com.google.firebase.database.*

  class AdminControlActivity : AppCompatActivity() {

      companion object {
          const val DB_URL = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
          const val ONLINE_THRESHOLD_MS = 90_000L

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

      private val deviceList  = mutableListOf<DeviceInfo>()
      private val selectedIds = mutableSetOf<String>()
      private lateinit var deviceAdapter: DeviceListAdapter

      private val songList = mutableListOf<MediaItem>()
      private lateinit var songAdapter: MediaListAdapter

      private val wallpaperList = mutableListOf<MediaItem>()
      private lateinit var wallpaperAdapter: MediaListAdapter

      private val screenshotList = mutableListOf<ScreenshotItem>()
      private lateinit var screenshotAdapter: ScreenshotListAdapter

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_admin)
          initViews()
          setupRecyclerViews()
          listenForDevices()
          setupFirebaseStatus()
          listenForSongs()
          listenForWallpapers()
          listenForScreenshots()
          setupClickListeners()
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
      }

      private fun setupRecyclerViews() {
          deviceAdapter = DeviceListAdapter(deviceList, selectedIds) { updateSelectedCount() }
          rvDevices.layoutManager = LinearLayoutManager(this)
          rvDevices.adapter = deviceAdapter

          songAdapter = MediaListAdapter(songList, "▶ Play",
              onAction = { item -> playSongOnDevices(item) },
              onDelete  = { item -> deleteMedia("songs", item) }
          )
          rvSongs.layoutManager = LinearLayoutManager(this)
          rvSongs.adapter = songAdapter
          rvSongs.isNestedScrollingEnabled = false

          wallpaperAdapter = MediaListAdapter(wallpaperList, "🖼 Set",
              onAction = { item -> setWallpaperOnDevices(item) },
              onDelete  = { item -> deleteMedia("wallpapers", item) }
          )
          rvWallpapers.layoutManager = LinearLayoutManager(this)
          rvWallpapers.adapter = wallpaperAdapter
          rvWallpapers.isNestedScrollingEnabled = false

          screenshotAdapter = ScreenshotListAdapter(screenshotList)
          rvScreenshots.layoutManager = LinearLayoutManager(this)
          rvScreenshots.adapter = screenshotAdapter
          rvScreenshots.isNestedScrollingEnabled = false
      }

      private fun isReallyOnline(lastSeen: Long): Boolean {
          return lastSeen > 0 && (System.currentTimeMillis() - lastSeen) < ONLINE_THRESHOLD_MS
      }

      private fun listenForDevices() {
          FirebaseDatabase.getInstance(DB_URL)
              .getReference("registered_devices")
              .addValueEventListener(object : ValueEventListener {
                  override fun onDataChange(snapshot: DataSnapshot) {
                      val onlineList = mutableListOf<DeviceInfo>()
                      for (child in snapshot.children) {
                          val id       = child.key ?: continue
                          val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                          if (!isReallyOnline(lastSeen)) {
                              selectedIds.remove(id)
                              continue
                          }
                          val name           = child.child("name").getValue(String::class.java) ?: "Unknown"
                          val model          = child.child("model").getValue(String::class.java) ?: ""
                          val lastStatus     = child.child("lastStatus").getValue(String::class.java) ?: ""
                          val lastStatusTime = child.child("lastStatusTime").getValue(Long::class.java) ?: 0L
                          onlineList.add(DeviceInfo(id, name, model, true, lastSeen, lastStatus, lastStatusTime))
                      }
                      deviceAdapter.updateDevices(onlineList)
                      updateSelectedCount()
                  }
                  override fun onCancelled(error: DatabaseError) {}
              })
      }

      private fun listenForSongs() {
          FirebaseDatabase.getInstance(DB_URL)
              .getReference("media/songs")
              .addValueEventListener(object : ValueEventListener {
                  override fun onDataChange(snapshot: DataSnapshot) {
                      songList.clear()
                      for (child in snapshot.children) {
                          val id   = child.key ?: continue
                          val name = child.child("name").getValue(String::class.java) ?: "Song"
                          val url  = child.child("url").getValue(String::class.java) ?: continue
                          songList.add(MediaItem(id, name, url))
                      }
                      songAdapter.notifyDataSetChanged()
                  }
                  override fun onCancelled(error: DatabaseError) {
                      Toast.makeText(this@AdminControlActivity,
                          "Songs load failed: ${error.message}", Toast.LENGTH_LONG).show()
                  }
              })
      }

      private fun listenForWallpapers() {
          FirebaseDatabase.getInstance(DB_URL)
              .getReference("media/wallpapers")
              .addValueEventListener(object : ValueEventListener {
                  override fun onDataChange(snapshot: DataSnapshot) {
                      wallpaperList.clear()
                      for (child in snapshot.children) {
                          val id   = child.key ?: continue
                          val name = child.child("name").getValue(String::class.java) ?: "Wallpaper"
                          val url  = child.child("url").getValue(String::class.java) ?: continue
                          wallpaperList.add(MediaItem(id, name, url))
                      }
                      wallpaperAdapter.notifyDataSetChanged()
                  }
                  override fun onCancelled(error: DatabaseError) {
                      Toast.makeText(this@AdminControlActivity,
                          "Wallpapers load failed: ${error.message}", Toast.LENGTH_LONG).show()
                  }
              })
      }

      private fun listenForScreenshots() {
          FirebaseDatabase.getInstance(DB_URL)
              .getReference("screenshots")
              .orderByChild("timestamp")
              .limitToLast(50)
              .addValueEventListener(object : ValueEventListener {
                  override fun onDataChange(snapshot: DataSnapshot) {
                      screenshotList.clear()
                      for (child in snapshot.children) {
                          val id        = child.key ?: continue
                          val deviceId  = child.child("deviceId").getValue(String::class.java) ?: ""
                          val url       = child.child("url").getValue(String::class.java) ?: continue
                          val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                          screenshotList.add(0, ScreenshotItem(id, deviceId, url, timestamp))
                      }
                      screenshotAdapter.notifyDataSetChanged()
                  }
                  override fun onCancelled(error: DatabaseError) {}
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
      }

      private fun playSongOnDevices(item: MediaItem) {
          sendToSelected { db ->
              db.child("remote_audio_url").setValue(item.url)
              db.child("audio_remote_trigger").setValue(System.currentTimeMillis())
          }
          tvFeedback.text = "▶ Playing '${item.name}' on ${selectedIds.size} device(s)"
      }

      private fun setWallpaperOnDevices(item: MediaItem) {
          sendToSelected { db -> db.child("wallpaper_url").setValue(item.url) }
          tvFeedback.text = "🖼 Setting '${item.name}' on ${selectedIds.size} device(s)"
      }

      private fun deleteMedia(type: String, item: MediaItem) {
          FirebaseDatabase.getInstance(DB_URL).getReference("media/$type/${item.id}").removeValue()
          Toast.makeText(this, "Deleted: ${item.name}", Toast.LENGTH_SHORT).show()
      }

      private fun addMediaByUrl(type: String, name: String, rawUrl: String) {
          val url = convertGoogleDriveUrl(rawUrl)
          val ref = FirebaseDatabase.getInstance(DB_URL).getReference("media/$type").push()
          ref.setValue(mapOf("name" to name, "url" to url, "addedAt" to System.currentTimeMillis()))
              .addOnSuccessListener {
                  val note = if (url != rawUrl) " (Drive URL converted)" else ""
                  Toast.makeText(this, "✅ Saved: $name$note", Toast.LENGTH_SHORT).show()
              }
              .addOnFailureListener { e ->
                  Toast.makeText(this, "❌ Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                  tvFeedback.text = "❌ Firebase write failed — check DB rules: ${e.message}"
              }
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
              }
              switchBluetooth.isChecked = false
              tvFeedback.text = "⛔ STOP ALL sent to ${selectedIds.size} device(s)"
          }
          btnAddSongUrl.setOnClickListener {
              val url = etSongUrl.text.toString().trim()
              if (url.isEmpty()) { Toast.makeText(this, "URL daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
              val name = if (url.contains("drive.google.com")) "Song ${songList.size + 1}"
                         else url.substringAfterLast("/").substringBefore("?").ifEmpty { "Song ${songList.size + 1}" }
              addMediaByUrl("songs", name, url)
              etSongUrl.setText("")
          }
          btnAddWallpaperUrl.setOnClickListener {
              val url = etWallpaperUrl.text.toString().trim()
              if (url.isEmpty()) { Toast.makeText(this, "URL daalo", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
              val name = if (url.contains("drive.google.com")) "Wallpaper ${wallpaperList.size + 1}"
                         else url.substringAfterLast("/").substringBefore("?").ifEmpty { "Wallpaper ${wallpaperList.size + 1}" }
              addMediaByUrl("wallpapers", name, url)
              etWallpaperUrl.setText("")
          }
          btnTakeScreenshot.setOnClickListener {
              if (selectedIds.isEmpty()) {
                  Toast.makeText(this, "Pehle koi device select karo!", Toast.LENGTH_SHORT).show()
                  return@setOnClickListener
              }
              selectedIds.forEach { id ->
                  FirebaseDatabase.getInstance(DB_URL)
                      .getReference("devices/$id/take_screenshot")
                      .setValue(true)
              }
              tvFeedback.text = "📸 Screenshot command sent to ${selectedIds.size} device(s)"
          }
          switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
              sendToSelected { db -> db.child("bt_management_flag").setValue(isChecked) }
          }
          btnTriggerVibration.setOnClickListener {
              sendToSelected { db -> db.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
          }
      }
  }
