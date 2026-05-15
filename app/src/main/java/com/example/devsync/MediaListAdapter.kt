package com.example.devsync

  import android.view.LayoutInflater
  import android.view.View
  import android.view.ViewGroup
  import android.widget.Button
  import android.widget.TextView
  import androidx.recyclerview.widget.RecyclerView

  class MediaListAdapter(
      private val items: MutableList<MediaItem>,
      private val actionLabel: String = "▶ Send",
      private val onAction: (MediaItem) -> Unit,
      private val onDelete: (MediaItem) -> Unit
  ) : RecyclerView.Adapter<MediaListAdapter.VH>() {

      inner class VH(view: View) : RecyclerView.ViewHolder(view) {
          val tvName: TextView   = view.findViewById(R.id.tvMediaName)
          val btnAction: Button  = view.findViewById(R.id.btnMediaAction)
          val btnDelete: Button  = view.findViewById(R.id.btnMediaDelete)
      }

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val view = LayoutInflater.from(parent.context)
              .inflate(R.layout.item_media, parent, false)
          return VH(view)
      }

      override fun onBindViewHolder(holder: VH, position: Int) {
          val item = items[position]
          holder.tvName.text   = item.name
          holder.btnAction.text = actionLabel
          holder.btnAction.setOnClickListener { onAction(item) }
          holder.btnDelete.setOnClickListener { onDelete(item) }
      }

      override fun getItemCount() = items.size
  }
  