package com.partypark.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.ContentObserver
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
import java.io.ByteArrayOutputStream
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
    private val observerHandler = Handler(observerThread.looper)
    private val resolver = appContext.contentResolver
    private val store = PhotoBackupStore(appContext)

    fun start() {
        val prefs = appContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PHOTO_BACKUP_ENABLED, false)) return
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        observerHandler.post { scanNewImages() }
    }

    fun stop() {
        try { resolver.unregisterContentObserver(this) } catch (_: Exception) {}
        observerThread.quitSafely()
    }

    fun scanNow() {
        if (appContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PHOTO_BACKUP_ENABLED, false)) {
            observerHandler.post { scanNewImages() }
        }
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
                    val safeBaseName = name.substringBeforeLast('.', name).replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val target = File(backupDir, "${capturedAt}_${id}_${safeBaseName}.jpg")
                    val sourceTemp = File(backupDir, "${capturedAt}_${id}_source.tmp")
                    try {
                        resolver.openInputStream(source)?.use { input ->
                            FileOutputStream(sourceTemp).use { output -> input.copyTo(output) }
                        } ?: continue
                        createUploadThumbnail(sourceTemp, target)
                        sourceTemp.delete()
                        if (!target.exists() || target.length() == 0L || target.length() > 900 * 1024L) {
                            target.delete()
                            continue
                        }
                        store.insert(source.toString(), target.absolutePath, "${safeBaseName}.jpg", target.length(), capturedAt)
                        PhotoBackupScheduler.enqueue(appContext)
                        Log.i("PhotoBackup", "Queued thumbnail: $name (${target.length()} bytes)")
                    } catch (error: Exception) {
                        sourceTemp.delete()
                        target.delete()
                        Log.e("PhotoBackup", "Could not create thumbnail for $name", error)
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

/**
 * Creates a JPEG thumbnail no larger than 900 KiB. The original is never uploaded.
 */
private fun createUploadThumbnail(source: File, target: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Unsupported image")
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
    var bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
        ?: throw IllegalArgumentException("Could not decode image")
    try {
        var quality = 85
        while (true) {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
            val encoded = bytes.toByteArray()
            if (encoded.size <= 900 * 1024) {
                FileOutputStream(target).use { it.write(encoded) }
                break
            }
            if (quality > 55) {
                quality -= 10
            } else {
                val resized = Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * 0.8).toInt().coerceAtLeast(1),
                    (bitmap.height * 0.8).toInt().coerceAtLeast(1), true)
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
                quality = 75
                if (bitmap.width < 320 || bitmap.height < 320) {
                    throw IllegalStateException("Could not compress thumbnail below 900 KiB")
                }
            }
        }
    } finally {
        bitmap.recycle()
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
        WorkManager.getInstance(context).enqueueUniqueWork("partypark-photo-upload", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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

/** Uploads compressed thumbnails, commits metadata, and only then removes the local copy. */
class PhotoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PHOTO_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PHOTO_BACKUP_ENABLED, false)) return Result.success()
        val deviceToken = prefs.getString("deviceToken", null) ?: return Result.failure()
        val store = PhotoBackupStore(applicationContext)
        val photo = store.all().firstOrNull() ?: return Result.success()
        val file = File(photo.path)

        // Local retention is based on capture time and is enforced even when offline.
        if (System.currentTimeMillis() - photo.capturedAt >= PHOTO_RETENTION_MS || !file.exists()) {
            file.delete()
            store.remove(photo.id)
            if (store.all().isNotEmpty()) PhotoBackupScheduler.enqueue(applicationContext)
            return Result.success()
        }

        return try {
            PhotoBackupApi.uploadPhoto(deviceToken, file, photo.name, photo.capturedAt)
            // Remove the local thumbnail only after Drive upload and D1 metadata commit succeed.
            file.delete()
            store.remove(photo.id)
            if (store.all().isNotEmpty()) PhotoBackupScheduler.enqueue(applicationContext)
            Result.success()
        } catch (error: Exception) {
            Log.e("PhotoBackup", "Cloudflare/Drive upload failed: ${photo.name}", error)
            Result.retry()
        }
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
