package com.example.devsync

  import android.os.Bundle
  import android.view.View
  import android.widget.Button
  import androidx.fragment.app.Fragment
  import androidx.recyclerview.widget.LinearLayoutManager
  import androidx.recyclerview.widget.RecyclerView
  import com.google.firebase.database.*

  class DevicesFragment : Fragment(R.layout.fragment_devices) {

      private val admin get() = requireActivity() as AdminControlActivity
      private lateinit var rvDevices: RecyclerView
      private lateinit var adapter: DeviceListAdapter

      // Only show online devices
      private val onlineDevices = mutableListOf<DeviceInfo>()

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          rvDevices = view.findViewById(R.id.rvDevices)
          adapter = DeviceListAdapter(onlineDevices, AdminControlActivity.selectedIds) {
              admin.updateSelectionBadge()
          }
          rvDevices.layoutManager = LinearLayoutManager(requireContext())
          rvDevices.adapter = adapter

          view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
              AdminControlActivity.selectedIds.addAll(onlineDevices.map { it.id })
              adapter.notifyDataSetChanged()
              admin.updateSelectionBadge()
          }

          view.findViewById<Button>(R.id.btnDeselectAll).setOnClickListener {
              AdminControlActivity.selectedIds.clear()
              adapter.notifyDataSetChanged()
              admin.updateSelectionBadge()
          }

          view.findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener {
              refreshOnlineList()
              admin.showStatus("↻ Refreshed", true)
          }

          listenForDevices()
      }

      fun refreshDeviceList() {
          refreshOnlineList()
      }

      private fun refreshOnlineList() {
          onlineDevices.clear()
          onlineDevices.addAll(AdminControlActivity.deviceList.filter { it.online })
          if (::adapter.isInitialized) adapter.notifyDataSetChanged()
      }

      private fun listenForDevices() {
          admin.db("registered_devices").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val now = System.currentTimeMillis()
                  AdminControlActivity.deviceList.clear()

                  snapshot.children.forEach { child ->
                      val id       = child.key ?: return@forEach
                      val name     = child.child("name").getValue(String::class.java) ?: id
                      val model    = child.child("model").getValue(String::class.java) ?: ""
                      val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                      val online   = (now - lastSeen) < AdminControlActivity.ONLINE_THRESHOLD_MS
                      val accOn    = child.child("accessibility_online").getValue(Boolean::class.java) ?: false
                      val statusTxt = when {
                          accOn && online -> "♿ Accessibility: ON"
                          online          -> "⚪ Accessibility: OFF"
                          else            -> ""
                      }
                      AdminControlActivity.deviceList.add(
                          DeviceInfo(id, name, model, online, lastSeen, statusTxt, 0L)
                      )
                  }

                  // Only online devices in list
                  refreshOnlineList()

                  val onlineCount = onlineDevices.size
                  activity?.runOnUiThread {
                      // Remove offline devices from selection
                      AdminControlActivity.selectedIds.retainAll(onlineDevices.map { it.id }.toSet())
                      admin.updateSelectionBadge()
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })
      }
  }
  