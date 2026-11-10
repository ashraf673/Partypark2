package com.partypark.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent?.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("Partypark", "Boot completed — scheduling stream service start")

            // Small delay so system services and permissions are ready
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val serviceIntent = Intent(context, StreamService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("Partypark", "Stream service started after boot")
                } catch (e: Exception) {
                    Log.e("Partypark", "Failed to start service after boot", e)
                }
            }, 8000) // 8 second delay
        }
    }
}