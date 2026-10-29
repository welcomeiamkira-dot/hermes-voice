package com.example.hermesvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val svc = Intent(context, VoiceService::class.java).apply {
                action = VoiceService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Exception) {
                // Android 14+ does not allow starting a microphone foreground service from boot.
                // Open the app once and tap Start Listening instead.
                Log.w("HermesVoice", "Could not auto-start service", e)
            }
        }
    }
}
