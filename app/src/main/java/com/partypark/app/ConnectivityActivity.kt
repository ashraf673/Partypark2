package com.partypark.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ConnectivityActivity : AppCompatActivity() {

    private lateinit var checkBattery: CheckBox
    private lateinit var checkOverlay: CheckBox
    private lateinit var checkNotification: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connectivity)

        checkBattery = findViewById(R.id.check_battery)
        checkOverlay = findViewById(R.id.check_overlay)
        checkNotification = findViewById(R.id.check_notification)

        findViewById<LinearLayout>(R.id.item_battery).setOnClickListener {
            openBatterySettings()
        }

        findViewById<LinearLayout>(R.id.item_overlay).setOnClickListener {
            openOverlaySettings()
        }

        findViewById<LinearLayout>(R.id.item_notification).setOnClickListener {
            openNotificationSettings()
        }

        updateChecks()
    }

    override fun onResume() {
        super.onResume()
        updateChecks()
    }

    private fun updateChecks() {
        checkBattery.isChecked = isBatteryOptimizationDisabled()
        checkOverlay.isChecked = Settings.canDrawOverlays(this)
        checkNotification.isChecked = isNotificationAccessGranted()
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(packageName) == true
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Unable to open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }
}