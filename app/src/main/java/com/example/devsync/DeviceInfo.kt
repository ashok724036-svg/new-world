package com.example.devsync

  data class DeviceInfo(
      val id: String = "",
      val name: String = "",
      val model: String = "",
      val online: Boolean = false,
      val lastSeen: Long = 0L,
      val lastStatus: String = "",
      val lastStatusTime: Long = 0L
  )
  