package com.arv.ario.data

data class ArioResponse(
    val type: String,      // "chat" or "command"
    val action: String?,   // "open_app", "play_media", "open_url", "none"
    val payload: String?,  // "com.instagram.android", "Believer song", "https://google.com"
    val speak: String      // The text Ario should speak to the user
)
