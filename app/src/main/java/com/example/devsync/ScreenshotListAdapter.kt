package com.example.devsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotListAdapter(
    private val items: MutableList<ScreenshotItem>
) : RecyclerView.Adapter<ScreenshotListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceId: TextView = view.findViewById(R.id.tvScreenshotDevice)
        val tvTime: TextView     = view.findViewById(R.id.tvScreenshotTime)
        val imgPreview: ImageView = view.findViewById(R.id.imgScreenshot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvDeviceId.text = "📱 ${item.deviceId.takeLast(6)}"
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        holder.tvTime.text = sdf.format(Date(item.timestamp))
        Glide.with(holder.itemView.context)
            .load(item.url)
            .placeholder(android.R.color.darker_gray)
            .into(holder.imgPreview)
    }

    override fun getItemCount() = items.size
}
