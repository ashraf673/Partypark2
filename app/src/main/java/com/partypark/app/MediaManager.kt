package com.partypark.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaManager(private val context: Context) {

    private val storage = Firebase.storage.reference
    private val database = Firebase.database.reference
    private val isScanning = AtomicBoolean(false)

    fun scanAndUploadMedia(ownerUid: String, deviceId: String, onComplete: (Int, Int) -> Unit = { _, _ -> }) {
        if (!isScanning.compareAndSet(false, true)) {
            Log.d("MediaManager", "Scan already in progress")
            onComplete(0, 0)
            return
        }

        Thread {
            var uploaded = 0
            var failed = 0
            try {
                val photos = queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
                val videos = queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
                val all = (photos + videos).sortedByDescending { it.date }

                for (item in all) {
                    if (!isScanning.get()) break

                    try {
                        val thumbBytes = createThumbnail(item.uri)
                        if (thumbBytes == null) {
                            failed++
                            continue
                        }

                        val stableId = "${item.type}_${item.id}"
                        val thumbPath = "media/$deviceId/${item.type}/${item.id}_thumb.jpg"
                        val thumbRef = storage.child(thumbPath)

                        val latch = CountDownLatch(1)
                        var success = false

                        thumbRef.putBytes(thumbBytes).continueWithTask { task ->
                            if (!task.isSuccessful) throw task.exception!!
                            thumbRef.downloadUrl
                        }.addOnSuccessListener { uri ->
                            val data = mapOf(
                                "id" to stableId,
                                "name" to item.name,
                                "type" to item.type,
                                "size" to item.size,
                                "duration" to item.duration,
                                "date" to item.date,
                                "thumbnail" to uri.toString()
                            )
                            database.child("users").child(ownerUid).child("devices").child(deviceId)
                                .child("media").child(stableId).setValue(data)
                                .addOnCompleteListener { dbTask ->
                                    success = dbTask.isSuccessful
                                    if (!success) {
                                        Log.e("MediaManager", "DB write failed for $stableId", dbTask.exception)
                                    }
                                    latch.countDown()
                                }
                        }.addOnFailureListener { e ->
                            Log.e("MediaManager", "Upload failed for ${item.name}", e)
                            latch.countDown()
                        }

                        val completed = latch.await(45, TimeUnit.SECONDS)
                        if (completed && success) {
                            uploaded++
                        } else {
                            failed++
                        }

                    } catch (e: Exception) {
                        Log.e("MediaManager", "Failed to process ${item.name}", e)
                        failed++
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaManager", "Scan failed", e)
            } finally {
                isScanning.set(false)
                onComplete(uploaded, failed)
            }
        }.start()
    }

    private fun queryMedia(uri: android.net.Uri, type: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val durationCol = if (type == "video") MediaStore.Video.Media.DURATION else null
        if (durationCol != null) projection.add(durationCol)

        context.contentResolver.query(
            uri,
            projection.toTypedArray(),
            null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol = durationCol?.let { cursor.getColumnIndexOrThrow(it) }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()
                val name = cursor.getString(nameCol) ?: "unknown"
                val size = cursor.getLong(sizeCol)
                val date = cursor.getLong(dateCol) * 1000
                val duration = durCol?.let { cursor.getLong(it) } ?: 0L
                val contentUri = ContentUris.withAppendedId(uri, id.toLong())
                items.add(MediaItem(id, name, type, size, duration, date, contentUri))
            }
        }
        return items
    }

    private fun createThumbnail(uri: android.net.Uri): ByteArray? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeStream(input, null, opts)
                }?.let { full ->
                    val scaled = Bitmap.createScaledBitmap(full, 200, 200, true)
                    if (scaled != full) full.recycle()
                    scaled
                }
            } ?: return null

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e("MediaManager", "Thumbnail failed", e)
            null
        }
    }

    data class MediaItem(
        val id: String,
        val name: String,
        val type: String,
        val size: Long,
        val duration: Long,
        val date: Long,
        val uri: android.net.Uri
    )
}