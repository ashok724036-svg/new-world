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

  class VideoListAdapter(
      private val items: MutableList<ScreenshotItem>
  ) : RecyclerView.Adapter<VideoListAdapter.VH>() {

      inner class VH(view: View) : RecyclerView.ViewHolder(view) {
          val tvName: TextView      = view.findViewById(R.id.tvVidName)
          val tvTime: TextView      = view.findViewById(R.id.tvVidTime)
          val btnDownload: Button   = view.findViewById(R.id.btnVidDownload)
          val btnView: Button       = view.findViewById(R.id.btnVidView)
      }

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
          return VH(v)
      }

      override fun onBindViewHolder(holder: VH, position: Int) {
          val item = items[position]
          val sdf  = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
          holder.tvName.text = item.fileName.substringAfterLast('/').take(40)
          holder.tvTime.text = if (item.timestamp > 0) sdf.format(Date(item.timestamp)) else "Unknown"

          holder.btnView.setOnClickListener {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
              intent.setDataAndType(Uri.parse(item.url), "video/mp4")
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              it.context.startActivity(intent)
          }

          holder.btnDownload.setOnClickListener {
              val ctx = it.context
              val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
              val req = DownloadManager.Request(Uri.parse(item.url))
                  .setTitle(item.fileName)
                  .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.fileName)
                  .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              dm.enqueue(req)
              Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
          }
      }

      override fun getItemCount() = items.size
  }
  