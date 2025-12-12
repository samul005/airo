package com.arv.ario.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow("Debug Console Ready...\n")
    val logs: StateFlow<String> = _logs.asStateFlow()

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // Prepend new logs to show latest at top
        _logs.value = "[$timestamp] $message\n" + _logs.value
    }

    fun logError(e: Throwable) {
        val msg = when (e) {
            is java.net.UnknownHostException -> "No Internet Connection. Check WiFi/Data."
            is java.net.SocketTimeoutException -> "Connection Timeout. Server is slow."
            is com.google.gson.JsonSyntaxException -> "JSON Parsing Failed. Response format is wrong."
            else -> "Error: ${e.javaClass.simpleName} - ${e.message}"
        }
        log("🔴 $msg")
    }
}
