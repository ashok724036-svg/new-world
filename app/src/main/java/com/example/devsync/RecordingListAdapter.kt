package com.example.devsync

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class RecordingListAdapter(
    private val items: MutableList<RecordingItem>
) : RecyclerView.Adapter<RecordingListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvType: TextView      = view.findViewById(R.id.tvRecordingType)
        val tvDevice: TextView    = view.findViewById(R.id.tvRecordingDevice)
        val tvTime: TextView      = view.findViewById(R.id.tvRecordingTime)
        val btnPlay: Button       = view.findViewById(R.id.btnPlayRecording)
        val btnDownload: Button   = view.findViewById(R.id.btnDownloadRecording)
        val btnDelete: Button     = view.findViewById(R.id.btnDeleteRecording)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx  = holder.itemView.context

        holder.tvType.text   = if (item.type == "call") "📞 Call Recording" else "🎙️ Mic Recording"
        holder.tvType.setTextColor(
            if (item.type == "call") ctx.getColor(android.R.color.holo_red_dark)
            else ctx.getColor(android.R.color.holo_blue_dark)
        )
        holder.tvDevice.text = "📱 ...${item.deviceId.takeLast(6)}"
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        holder.tvTime.text   = sdf.format(Date(item.timestamp))

        holder.btnPlay.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(item.url), "audio/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Player nahi mila", Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnDownload.setOnClickListener {
            try {
                val fileName = if (item.fileName.isNotEmpty()) item.fileName
                               else "${item.type}_${System.currentTimeMillis()}.m4a"
                val req = DownloadManager.Request(Uri.parse(item.url)).apply {
                    setTitle("Recording downloading...")
                    setDescription(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setMimeType("audio/mp4")
                }
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "⬇ Download shuru: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnDelete.setOnClickListener {
            FirebaseDatabase.getInstance(RecordingService.DB_URL)
                .getReference("recordings/${item.id}").removeValue()
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
