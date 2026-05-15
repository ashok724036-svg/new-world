package com.example.devsync

data class ChatMessage(
    val message: String,
    val isUser: Boolean   // true = user (right side), false = AI (left side)
)