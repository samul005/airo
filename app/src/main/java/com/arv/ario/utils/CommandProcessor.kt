package com.arv.ario.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.arv.ario.data.ArioResponse

object CommandProcessor {
    fun execute(context: Context, response: ArioResponse) {
        Log.d("CommandProcessor", "Executing: $response")
        when (response.action) {
            "open_app" -> openApp(context, response.payload)
            "open_url" -> openUrl(context, response.payload)
            "play_media" -> playMedia(context, response.payload)
            "system_setting" -> openSystemSetting(context, response.payload)
        }
    }

    private fun openApp(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Log.e("CommandProcessor", "App not found: $packageName")
            }
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Error opening app", e)
        }
    }

    private fun openUrl(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Error opening URL", e)
        }
    }

    private fun playMedia(context: Context, query: String?) {
        if (query.isNullOrEmpty()) return
        try {
            // Try to open YouTube search
            val intent = Intent(Intent.ACTION_SEARCH)
            intent.setPackage("com.google.android.youtube")
            intent.putExtra("query", query)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser YouTube search
            openUrl(context, "https://www.youtube.com/results?search_query=$query")
        }
    }
    
    private fun openSystemSetting(context: Context, settingName: String?) {
         val intent = when(settingName?.lowercase()) {
             "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
             "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
             "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
             else -> Intent(Settings.ACTION_SETTINGS)
         }
         intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
         context.startActivity(intent)
    }
}
