package com.example.devsync

  import android.graphics.Color
  import android.view.*
  import android.widget.*
  import androidx.recyclerview.widget.RecyclerView

  class DeviceListAdapter(
      private val devices: MutableList<DeviceInfo>,
      private val selectedIds: MutableSet<String>,
      private val onSelectionChanged: () -> Unit = {}
  ) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

      inner class VH(view: View) : RecyclerView.ViewHolder(view) {
          val checkbox: CheckBox     = view.findViewById(R.id.cbDevice)
          val tvName: TextView       = view.findViewById(R.id.tvDeviceName)
          val tvStatus: TextView     = view.findViewById(R.id.tvDeviceStatus)
          val tvTaskStatus: TextView = view.findViewById(R.id.tvTaskStatus)
      }

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val view = LayoutInflater.from(parent.context)
              .inflate(R.layout.item_device, parent, false)
          return VH(view)
      }

      override fun onBindViewHolder(holder: VH, position: Int) {
          val device = devices[position]

          holder.tvName.text   = device.name.ifEmpty { device.model }
          holder.tvStatus.text = "● ONLINE"
          holder.tvStatus.setTextColor(Color.parseColor("#2E7D32"))

          if (device.lastStatus.isNotEmpty()) {
              val tDiff = System.currentTimeMillis() - device.lastStatusTime
              val when_ = when {
                  tDiff < 60_000L    -> "just now"
                  tDiff < 3_600_000L -> "${tDiff / 60_000}m ago"
                  else               -> "${tDiff / 3_600_000}h ago"
              }
              holder.tvTaskStatus.text      = "${device.lastStatus}  ($when_)"
              holder.tvTaskStatus.visibility = View.VISIBLE
          } else {
              holder.tvTaskStatus.text       = ""
              holder.tvTaskStatus.visibility = View.GONE
          }

          holder.checkbox.setOnCheckedChangeListener(null)
          holder.checkbox.isChecked = selectedIds.contains(device.id)
          holder.checkbox.setOnCheckedChangeListener { _, checked ->
              if (checked) selectedIds.add(device.id) else selectedIds.remove(device.id)
              onSelectionChanged()
          }
      }

      override fun getItemCount() = devices.size

      fun updateDevices(newList: List<DeviceInfo>) {
          devices.clear()
          devices.addAll(newList)
          notifyDataSetChanged()
      }
  }
  