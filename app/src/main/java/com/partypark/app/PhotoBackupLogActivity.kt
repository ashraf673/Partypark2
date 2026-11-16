package com.partypark.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class PhotoBackupLogActivity : AppCompatActivity() {
    private lateinit var logText: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20); setBackgroundColor(0xFF1A0B2E.toInt()) }
        val title = TextView(this).apply { text = "Photo Backup Logs"; textSize = 22f; setTextColor(-1) }
        root.addView(title)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) {
            actions.addView(Button(this).apply { text = label; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("Copy") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Partypark photo backup logs", logText.text))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }
        button("Share") {
            try {
                val file = PhotoBackupLogger.fileForShare(this)
                val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Partypark photo backup logs")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share diagnostic log"))
            } catch (e: Exception) { Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        button("Clear") {
            PhotoBackupLogger.clear(this); refresh()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(actions)
        val scroll = ScrollView(this)
        logText = TextView(this).apply { textSize = 12f; setTextColor(0xFFE2E8F0.toInt()); setTextIsSelectable(true); setPadding(8, 16, 8, 16) }
        scroll.addView(logText)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }
    private fun refresh() { logText.text = PhotoBackupLogger.read(this) }
}