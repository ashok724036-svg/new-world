package com.example.devsync

  import android.view.*
  import android.widget.*
  import androidx.recyclerview.widget.RecyclerView

  data class SavedUrl(val name: String, val url: String)

  class SavedUrlAdapter(
      private val items: MutableList<SavedUrl>,
      private val actionLabel: String,
      private val onAction: (name: String, url: String) -> Unit,
      private val onDelete: (index: Int) -> Unit
  ) : RecyclerView.Adapter<SavedUrlAdapter.VH>() {

      inner class VH(view: View) : RecyclerView.ViewHolder(view) {
          val tvName: TextView    = view.findViewById(R.id.tvSavedName)
          val btnAction: Button   = view.findViewById(R.id.btnSavedAction)
          val btnDelete: Button   = view.findViewById(R.id.btnSavedDelete)
      }

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val v = LayoutInflater.from(parent.context).inflate(R.layout.item_saved_url, parent, false)
          return VH(v)
      }

      override fun onBindViewHolder(holder: VH, position: Int) {
          val item = items[position]
          holder.tvName.text = item.name
          holder.btnAction.text = actionLabel
          holder.btnAction.setOnClickListener { onAction(item.name, item.url) }
          holder.btnDelete.setOnClickListener { onDelete(position) }
      }

      override fun getItemCount() = items.size
  }
  