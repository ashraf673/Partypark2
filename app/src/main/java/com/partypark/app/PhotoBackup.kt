package com.partypark.app

import android.content.ContentObserver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val PHOTO_PREFS = "partypark"
private const val PHOTO_BACKUP_ENABLED = "photoBackupEnabled"
private const val PHOTO_BACKUP_ENABLED_AT = "photoBackupEnabledAt"
private const val PHOTO_RETENTION_MS = 20L * 24 * 60 * 60 * 1000

/**
 * Consent-gated observer. It starts from the explicit opt-in timestamp, so
 * enabling the feature never imports the phone's existing gallery.
 */
class PhotoBackupObserver private constructor(context: Context, private val observerThread: HandlerThread) : ContentObserver(Handler(observerThread.looper)) {
    constructor(context: Context) : this(context.applicationContext, HandlerThread("PartyparkPhotoObserver").apply { start() })

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val store = PhotoBackupStore(appContext)

    fun start() {
        val prefs = appContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PHOTO_BACKUP_ENABLED, false)) return
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        scanNewImages()
    }

    fun stop() {
        try { resolver.unregisterContentObserver(this) } catch (_: Exception) {}
        observerThread.quitSafely()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        if (appContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PHOTO_BACKUP_ENABLED, false)) {
            scanNewImages()
        }
    }

    private fun scanNewImages() {
        val prefs = appContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        val enabledAt = prefs.getLong(PHOTO_BACKUP_ENABLED_AT, System.currentTimeMillis())
        val enabledAtSeconds = enabledAt / 1000
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(enabledAtSeconds.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val source = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    if (store.contains(source.toString())) continue
                    val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                    val capturedAt = cursor.getLong(dateCol) * 1000L
                    if (capturedAt < enabledAt) continue
                    val backupDir = File(appContext.filesDir, "photo-backup").apply { mkdirs() }
                    val target = File(backupDir, "${capturedAt}_${id}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    try {
                        resolver.openInputStream(source)?.use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        } ?: continue
                        if (!target.exists() || target.length() == 0L) {
                            target.delete()
                            continue
                        }
                        store.insert(source.toString(), target.absolutePath, name, target.length(), capturedAt)
                        PhotoBackupScheduler.enqueue(appContext)
                        Log.i("PhotoBackup", "Queued new photo: $name (${target.length()} bytes)")
                    } catch (error: Exception) {
                        target.delete()
                        Log.e("PhotoBackup", "Could not copy new photo $name", error)
                    }
                }
            }
        } catch (error: SecurityException) {
            Log.e("PhotoBackup", "Photo permission is missing or limited", error)
        } catch (error: Exception) {
            Log.e("PhotoBackup", "Media observer scan failed", error)
        }
    }
}

internal class PhotoBackupStore(context: Context) :
    SQLiteOpenHelper(context, "photo_backup_queue.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, source_uri TEXT UNIQUE, " +
                "local_path TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, " +
                "captured_at INTEGER NOT NULL)"
        )
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun contains(uri: String): Boolean =
        readableDatabase.rawQuery("SELECT id FROM queue WHERE source_uri=?", arrayOf(uri)).use { it.moveToFirst() }

    @Synchronized fun insert(uri: String, path: String, name: String, size: Long, date: Long) {
        val values = ContentValues().apply {
            put("source_uri", uri); put("local_path", path); put("name", name)
            put("size", size); put("captured_at", date)
        }
        writableDatabase.insertWithOnConflict("queue", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun all(): List<QueuedPhoto> {
        val rows = mutableListOf<QueuedPhoto>()
        readableDatabase.rawQuery(
            "SELECT id, local_path, name, size, captured_at FROM queue ORDER BY captured_at ASC", null
        ).use { c ->
            while (c.moveToNext()) rows.add(QueuedPhoto(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)))
        }
        return rows
    }

    @Synchronized fun remove(id: Long) { writableDatabase.delete("queue", "id=?", arrayOf(id.toString())) }
}

internal data class QueuedPhoto(val id: Long, val path: String, val name: String, val size: Long, val capturedAt: Long)

object PhotoBackupScheduler {
    fun enqueue(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<PhotoBackupWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("partypark-photo-upload", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<PhotoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "partypark-photo-upload-periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    // Runs even without internet so the 20-day local retention rule is enforced offline.
    fun scheduleMaintenance(context: Context) {
        val request = PeriodicWorkRequestBuilder<PhotoBackupMaintenanceWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "partypark-photo-local-retention", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}

/** Uploads original files, commits metadata, and only then removes the local copy. */
class PhotoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PHOTO_BACKUP_ENABLED, false)) return Result.success()
        val ownerUid = prefs.getString("ownerUid", null) ?: return Result.failure()
        val deviceId = prefs.getString("deviceId", null) ?: return Result.failure()
        val store = PhotoBackupStore(applicationContext)
        val now = System.currentTimeMillis()
        var hadFailure = false

        for (photo in store.all()) {
            val file = File(photo.path)
            if (now - photo.capturedAt >= PHOTO_RETENTION_MS) {
                file.delete()
                store.remove(photo.id)
                continue
            }
            if (!file.exists()) {
                store.remove(photo.id)
                continue
            }
            try {
                val key = "img_${photo.capturedAt}_${photo.id}"
                val storagePath = "media/$deviceId/originals/$key"
                val ref = Firebase.storage.reference.child(storagePath)
                val uploadLatch = CountDownLatch(1)
                var uploadError: Exception? = null
                var downloadUrl = ""
                ref.putFile(Uri.fromFile(file))
                    .continueWithTask { task ->
                        if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Upload failed")
                        ref.downloadUrl
                    }
                    .addOnSuccessListener { uri -> downloadUrl = uri.toString(); uploadLatch.countDown() }
                    .addOnFailureListener { error -> uploadError = error; uploadLatch.countDown() }
                if (!uploadLatch.await(10, TimeUnit.MINUTES) || uploadError != null) {
                    hadFailure = true
                    Log.e("PhotoBackup", "Upload failed: ${photo.name}", uploadError)
                    continue
                }

                val record = mapOf(
                    "id" to key, "name" to photo.name, "type" to "image",
                    "size" to photo.size, "date" to photo.capturedAt,
                    "uploadedAt" to System.currentTimeMillis(),
                    "storagePath" to storagePath, "downloadURL" to downloadUrl
                )
                val dbLatch = CountDownLatch(1)
                var dbOk = false
                Firebase.database.reference.child("users").child(ownerUid)
                    .child("devices").child(deviceId).child("media").child(key)
                    .setValue(record)
                    .addOnCompleteListener { task -> dbOk = task.isSuccessful; dbLatch.countDown() }
                if (!dbLatch.await(60, TimeUnit.SECONDS) || !dbOk) {
                    hadFailure = true
                    Log.e("PhotoBackup", "Metadata write failed; local copy retained for retry: ${photo.name}")
                    continue
                }
                file.delete()
                store.remove(photo.id)
            } catch (error: Exception) {
                hadFailure = true
                Log.e("PhotoBackup", "Queue item failed: ${photo.name}", error)
            }
        }
        return if (hadFailure) Result.retry() else Result.success()
    }
}

/** Local-only cleanup; deliberately has no network constraint. */
class PhotoBackupMaintenanceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = PhotoBackupStore(applicationContext)
        val cutoff = System.currentTimeMillis() - PHOTO_RETENTION_MS
        store.all().filter { it.capturedAt <= cutoff }.forEach { photo ->
            File(photo.path).delete()
            store.remove(photo.id)
        }
        return Result.success()
    }
}
