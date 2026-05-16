package com.example.devsync

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class RecordingListAdapter(
    private val items: MutableList<RecordingItem>
) : RecyclerView.Adapter<RecordingListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView    = view.findViewById(R.id.tvRecordingType)
        val tvDevice: TextView  = view.findViewById(R.id.tvRecordingDevice)
        val tvTime: TextView    = view.findViewById(R.id.tvRecordingTime)
        val btnPlay: Button     = view.findViewById(R.id.btnPlayRecording)
        val btnDownload: Button = view.findViewById(R.id.btnDownloadRecording)
        val btnDelete: Button   = view.findViewById(R.id.btnDeleteRecording)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx  = holder.itemView.context

        val isVideo = item.type == "video"
        holder.tvType.text = when (item.type) {
            "call"  -> "📞 Call Recording"
            "video" -> "🎬 Screen Video"
            else    -> "🎙️ Mic Recording"
        }
        holder.tvType.setTextColor(when (item.type) {
            "call"  -> ctx.getColor(android.R.color.holo_red_dark)
            "video" -> ctx.getColor(android.R.color.holo_purple)
            else    -> ctx.getColor(android.R.color.holo_blue_dark)
        })
        holder.tvDevice.text = "📱 ...${item.deviceId.takeLast(6)}"
        holder.tvTime.text = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            .format(Date(item.timestamp))

        holder.btnPlay.text = if (isVideo) "▶ Video" else "▶ Play"
        holder.btnPlay.setOnClickListener {
            try {
                val mime = if (isVideo) "video/*" else "audio/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(item.url), mime)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Player nahi mila", Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnDownload.setOnClickListener {
            try {
                val ext      = if (isVideo) "mp4" else "m4a"
                val mime     = if (isVideo) "video/mp4" else "audio/mp4"
                val fileName = item.fileName.ifEmpty { "${item.type}_${System.currentTimeMillis()}.$ext" }
                val req = DownloadManager.Request(Uri.parse(item.url)).apply {
                    setTitle("Downloading $fileName")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setMimeType(mime)
                }
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "⬇ Download shuru: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        val dbPath = if (isVideo) "live_videos/${item.id}" else "recordings/${item.id}"
        holder.btnDelete.setOnClickListener {
            FirebaseDatabase.getInstance(AdminControlActivity.DB_URL)
                .getReference(dbPath).removeValue()
            Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<RecordingItem>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.timestamp })
        notifyDataSetChanged()
    }
}
