package com.example.devsync

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotListAdapter(
    private val items: MutableList<ScreenshotItem>
) : RecyclerView.Adapter<ScreenshotListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceId: TextView   = view.findViewById(R.id.tvScreenshotDevice)
        val tvTime: TextView       = view.findViewById(R.id.tvScreenshotTime)
        val imgPreview: ImageView  = view.findViewById(R.id.imgScreenshot)
        val btnView: Button        = view.findViewById(R.id.btnViewScreenshot)
        val btnDownload: Button    = view.findViewById(R.id.btnDownloadScreenshot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx  = holder.itemView.context

        holder.tvDeviceId.text = "📱 ${item.deviceId.takeLast(6)}"
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        holder.tvTime.text = sdf.format(Date(item.timestamp))

        Glide.with(ctx)
            .load(item.url)
            .placeholder(android.R.color.darker_gray)
            .error(android.R.color.holo_red_light)
            .into(holder.imgPreview)

        // Image tap → View full screen in browser
        holder.imgPreview.setOnClickListener {
            openInBrowser(ctx, item.url)
        }

        // View button → open in browser
        holder.btnView.setOnClickListener {
            openInBrowser(ctx, item.url)
        }

        // Download button → save to Downloads folder
        holder.btnDownload.setOnClickListener {
            downloadFile(ctx, item.url, item.deviceId, item.timestamp)
        }
    }

    private fun openInBrowser(ctx: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Browser open nahi hua", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile(ctx: Context, url: String, deviceId: String, timestamp: Long) {
        try {
            val sdf      = SimpleDateFormat("ddMMM_HHmm", Locale.getDefault())
            val fileName = "screenshot_${deviceId.takeLast(4)}_${sdf.format(Date(timestamp))}.jpg"

            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Screenshot downloading...")
                setDescription(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("image/jpeg")
            }

            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(ctx, "⬇ Download shuru ho gaya: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<ScreenshotItem>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.timestamp })
        notifyDataSetChanged()
    }
}
