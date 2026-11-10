package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = Firebase.auth
        requestBasicPermissions()

        val isActivated = prefs.getBoolean("activated", false)
        val savedName = prefs.getString("deviceName", null)

        if (isActivated && savedName != null) {
            showHomeScreen(savedName)
            startBackgroundService()
        } else {
            showSetupScreen()
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showHomeScreen(name: String) {
        setContentView(R.layout.activity_home)
        findViewById<TextView>(R.id.device_name_text).text = name
        findViewById<TextView>(R.id.status_text).text = "Online • Ready"

        findViewById<TextView>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showSetupScreen() {
        setContentView(R.layout.activity_main)

        val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
        val nameInput = findViewById<TextInputEditText>(R.id.name_input)
        val doneBtn = findViewById<MaterialButton>(R.id.done_btn)
        val scannerContainer = findViewById<android.widget.FrameLayout>(R.id.scanner_container)

        val isActivated = prefs.getBoolean("activated", false)
        if (isActivated) {
            activateCodeBtn.text = "Activated"
            activateCodeBtn.isEnabled = false
            activateCodeBtn.alpha = 0.5f
        }

        activateCodeBtn.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                showActivationDialog()
            }
        }

        scannerContainer.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                Toast.makeText(this, "QR scanning coming soon. Use code for now.", Toast.LENGTH_SHORT).show()
            }
        }

        doneBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ownerUid = prefs.getString("ownerUid", null)
            if (ownerUid != null) {
                prefs.edit().putString("deviceName", name).apply()
                registerDevice(ownerUid, name)
                Toast.makeText(this, "Setup complete", Toast.LENGTH_SHORT).show()
                showHomeScreen(name)
                startBackgroundService()
            } else {
                Toast.makeText(this, "Activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showActivationDialog() {
        val input = EditText(this)
        input.hint = "Enter activation code"

        AlertDialog.Builder(this)
            .setTitle("Activate with Code")
            .setView(input)
            .setPositiveButton("Activate") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    verifyActivationCode(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyActivationCode(code: String) {
        val db = Firebase.database.reference
        db.child("activationCodes").child(code).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val ownerUid = snapshot.child("uid").value as? String
                if (ownerUid != null) {
                    registerDevice(ownerUid, null)
                    prefs.edit()
                        .putBoolean("activated", true)
                        .putString("ownerUid", ownerUid)
                        .apply()

                    val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
                    activateCodeBtn.text = "Activated"
                    activateCodeBtn.isEnabled = false
                    activateCodeBtn.alpha = 0.5f

                    Toast.makeText(this, "App activated successfully", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Invalid activation code", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDevice(ownerUid: String, name: String?) {
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

        val model = Build.MODEL ?: "Unknown Device"
        val displayName = if (name != null) "$name • $model" else model

        val deviceData = mapOf(
            "name" to displayName,
            "online" to true,
            "lastSeen" to System.currentTimeMillis()
        )

        Firebase.database.reference
            .child("users")
            .child(ownerUid)
            .child("devices")
            .child(deviceId)
            .setValue(deviceData)
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }
}