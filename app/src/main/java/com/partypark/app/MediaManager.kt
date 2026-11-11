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
import java.util.concurrent.atomic.AtomicBoolean

class MediaManager(private val context: Context) {
    private val storage = Firebase.storage.reference
    private val database = Firebase.database.reference
    private val isScanning = AtomicBoolean(false)
    private val maxThumbnailBytes = 100 * 1024

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
                        if (thumbBytes == null || thumbBytes.size > maxThumbnailBytes) {
                            failed++
                            continue
                        }

                        val stableId = "${item.type}_${item.id}"
                        val thumbRef = storage.child("media/$deviceId/${item.type}/${item.id}_thumb.jpg")
                        val latch = CountDownLatch(1)
                        var success = false

                        // Do not move to the next item until both Storage and the DB write finish.
                        thumbRef.putBytes(thumbBytes)
                            .continueWithTask { task ->
                                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Thumbnail upload failed")
                                thumbRef.downloadUrl
                            }
                            .addOnSuccessListener { downloadUri ->
                                val data = mapOf(
                                    "id" to stableId,
                                    "name" to item.name,
                                    "type" to item.type,
                                    "size" to item.size,
                                    "duration" to item.duration,
                                    "date" to item.date,
                                    "thumbnail" to downloadUri.toString(),
                                    "thumbnailSize" to thumbBytes.size
                                )
                                database.child("users").child(ownerUid).child("devices").child(deviceId)
                                    .child("media").child(stableId).setValue(data)
                                    .addOnCompleteListener { dbTask ->
                                        success = dbTask.isSuccessful
                                        if (!success) Log.e("MediaManager", "DB write failed for $stableId", dbTask.exception)
                                        latch.countDown()
                                    }
                            }
                            .addOnFailureListener { error ->
                                Log.e("MediaManager", "Thumbnail upload failed for ${item.name}", error)
                                latch.countDown()
                            }

                        // No timeout here: timing out would allow the next upload to overlap this one.
                        latch.await()
                        if (success) uploaded++ else failed++
                    } catch (error: Exception) {
                        Log.e("MediaManager", "Failed to process ${item.name}", error)
                        failed++
                    }
                }
            } catch (error: Exception) {
                Log.e("MediaManager", "Media scan failed", error)
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

        context.contentResolver.query(uri, projection.toTypedArray(), null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol = durationCol?.let { cursor.getColumnIndexOrThrow(it) }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items.add(MediaItem(
                    id.toString(),
                    cursor.getString(nameCol) ?: "unknown",
                    type,
                    cursor.getLong(sizeCol),
                    durCol?.let { cursor.getLong(it) } ?: 0L,
                    cursor.getLong(dateCol) * 1000,
                    ContentUris.withAppendedId(uri, id)
                ))
            }
        }
        return items
    }

    private fun createThumbnail(uri: android.net.Uri): ByteArray? {
        var bitmap: Bitmap? = null
        try {
            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 8 })
                }?.let { decoded ->
                    val scaled = Bitmap.createScaledBitmap(decoded, 200, 200, true)
                    if (scaled !== decoded) decoded.recycle()
                    scaled
                }
            } ?: return null

            var current = bitmap
            var quality = 75
            var bytes: ByteArray
            do {
                val output = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, quality, output)
                bytes = output.toByteArray()
                if (bytes.size <= maxThumbnailBytes) return bytes
                if (quality > 45) {
                    quality -= 10
                } else if (current.width > 64 || current.height > 64) {
                    val smaller = Bitmap.createScaledBitmap(
                        current,
                        (current.width * 0.75f).toInt().coerceAtLeast(1),
                        (current.height * 0.75f).toInt().coerceAtLeast(1),
                        true
                    )
                    if (smaller !== current) current.recycle()
                    current = smaller
                    quality = 65
                } else {
                    return null
                }
            } while (true)
        } catch (error: Exception) {
            Log.e("MediaManager", "Thumbnail creation failed", error)
            return null
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
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