package com.example.devsync

  import android.os.Bundle
  import android.provider.Settings
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

      override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
          super.onViewCreated(view, savedInstanceState)

          rvDevices = view.findViewById(R.id.rvDevices)
          adapter = DeviceListAdapter(AdminControlActivity.deviceList, AdminControlActivity.selectedIds) {
              admin.updateSelectionBadge()
          }
          rvDevices.layoutManager = LinearLayoutManager(requireContext())
          rvDevices.adapter = adapter

          view.findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
              AdminControlActivity.selectedIds.addAll(AdminControlActivity.deviceList.map { it.id })
              adapter.notifyDataSetChanged()
              admin.updateSelectionBadge()
          }

          view.findViewById<Button>(R.id.btnDeselectAll).setOnClickListener {
              AdminControlActivity.selectedIds.clear()
              adapter.notifyDataSetChanged()
              admin.updateSelectionBadge()
          }

          view.findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener {
              adapter.notifyDataSetChanged()
              admin.showStatus("↻ Device list refreshed", true)
          }

          listenForAccessibilityStatus()
      }

      fun refreshDeviceList() {
          if (::adapter.isInitialized) adapter.notifyDataSetChanged()
      }

      private fun listenForAccessibilityStatus() {
          admin.db("registered_devices").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val now = System.currentTimeMillis()
                  snapshot.children.forEach { child ->
                      val id = child.key ?: return@forEach
                      val accessOnline = child.child("accessibility_online").getValue(Boolean::class.java) ?: false
                      val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                      val deviceOnline = (now - lastSeen) < AdminControlActivity.ONLINE_THRESHOLD_MS
                      val idx = AdminControlActivity.deviceList.indexOfFirst { it.id == id }
                      if (idx >= 0) {
                          val d = AdminControlActivity.deviceList[idx]
                          AdminControlActivity.deviceList[idx] = d.copy(
                              online = deviceOnline,
                              lastStatus = if (accessOnline) "♿ Accessibility: ON" else "⚪ Accessibility: OFF"
                          )
                      }
                  }
                  if (::adapter.isInitialized) adapter.notifyDataSetChanged()
              }
              override fun onCancelled(error: DatabaseError) {}
          })
      }
  }
  