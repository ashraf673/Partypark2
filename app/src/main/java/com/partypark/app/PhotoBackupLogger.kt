package com.partypark.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small local diagnostic log. Never records tokens, image bytes, or authorization headers. */
internal object PhotoBackupLogger {
    private const val MAX_BYTES = 1024L * 1024L
    private fun file(context: Context) = File(context.applicationContext.filesDir, "photo-backup-debug.log")

    @Synchronized fun log(context: Context, stage: String, message: String) {
        try {
            val f = file(context)
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = f.readLines().takeLast(1500)
                f.writeText(old.joinToString("\n", postfix = "\n"))
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$time [$stage] $message\n")
        } catch (_: Exception) { }
    }

    @Synchronized fun read(context: Context): String =
        try { file(context).takeIf { it.exists() }?.readText() ?: "No diagnostic events yet. Enable backup and take a new photo." }
        catch (e: Exception) { "Could not read diagnostic log: ${e.javaClass.simpleName}" }

    @Synchronized fun clear(context: Context) {
        try { file(context).writeText("") } catch (_: Exception) { }
    }

    fun fileForShare(context: Context): File {
        val out = File(context.cacheDir, "partypark-photo-backup-log.txt")
        out.writeText(read(context))
        return out
    }
}