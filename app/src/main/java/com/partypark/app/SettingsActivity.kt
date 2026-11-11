package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }
    private lateinit var photoBackupSwitch: SwitchMaterial

    private val imagePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enablePhotoBackup() else {
            photoBackupSwitch.isChecked = false
            Toast.makeText(this, "Photo access is required for automatic backup", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.improve_connectivity_item).setOnClickListener {
            startActivity(Intent(this, ConnectivityActivity::class.java))
        }

        photoBackupSwitch = findViewById(R.id.photo_backup_switch)
        photoBackupSwitch.isChecked = prefs.getBoolean("photoBackupEnabled", false)
        photoBackupSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                    enablePhotoBackup()
                } else {
                    imagePermissionRequest.launch(permission)
                }
            } else {
                prefs.edit().putBoolean("photoBackupEnabled", false).apply()
                startOrRefreshService()
                Toast.makeText(this, "Automatic photo backup paused", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.nav_home).setOnClickListener { finish() }
    }

    private fun enablePhotoBackup() {
        // Reset the watermark on every opt-in. Photos created while backup was paused are not imported.
        prefs.edit()
            .putLong("photoBackupEnabledAt", System.currentTimeMillis())
            .putBoolean("photoBackupEnabled", true)
            .apply()
        photoBackupSwitch.isChecked = true
        startOrRefreshService()
        Toast.makeText(this, "Automatic backup enabled for new photos", Toast.LENGTH_LONG).show()
    }

    private fun startOrRefreshService() {
        val intent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
