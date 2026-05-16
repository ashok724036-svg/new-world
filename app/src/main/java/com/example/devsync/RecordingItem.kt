package com.example.devsync

data class RecordingItem(
    val id: String,
    val deviceId: String,
    val type: String,        // "mic" or "call"
    val url: String,
    val timestamp: Long,
    val fileName: String = ""
)
