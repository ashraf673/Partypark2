package com.partypark.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.improve_connectivity_item).setOnClickListener {
            startActivity(Intent(this, ConnectivityActivity::class.java))
        }

        findViewById<TextView>(R.id.nav_home).setOnClickListener {
            finish()
        }
    }
}