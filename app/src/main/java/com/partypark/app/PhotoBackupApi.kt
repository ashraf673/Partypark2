package com.partypark.app

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object PhotoBackupApi {
    private const val BASE_URL = "https://partypark-backend.a6h7a3.workers.dev"
    private const val CHUNK_SIZE = 8 * 1024 * 1024

    data class DeviceCredentials(val deviceId: String, val deviceToken: String, val ownerUid: String)

    private fun request(
        path: String,
        method: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
        contentRange: String? = null
    ): Pair<Int, ByteArray> {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30000
            readTimeout = 120000
            doInput = true
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                if (contentRange != null) setRequestProperty("Content-Range", contentRange)
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return status to bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonRequest(path: String, method: String, token: String? = null, data: JSONObject): JSONObject {
        val (status, bytes) = request(path, method, token, data.toString().toByteArray(Charsets.UTF_8))
        val response = if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)) else JSONObject()
        if (status !in 200..299) throw IllegalStateException(response.optString("error", "Backend request failed ($status)"))
        return response
    }

    fun redeemActivationCode(code: String, deviceId: String, deviceName: String): DeviceCredentials {
        val result = jsonRequest(
            "/v1/devices/redeem",
            "POST",
            data = JSONObject()
                .put("code", code.filter { it.isDigit() })
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
        )
        val token = result.optString("deviceToken")
        val ownerUid = result.optString("ownerUid")
        if (token.isBlank() || ownerUid.isBlank()) throw IllegalStateException("Backend returned incomplete device credentials")
        return DeviceCredentials(result.getString("deviceId"), token, ownerUid)
    }

    fun uploadPhoto(deviceToken: String, file: File, fileName: String, capturedAt: Long) {
        val session = jsonRequest(
            "/v1/photos/upload-sessions",
            "POST",
            deviceToken,
            JSONObject().put("fileName", fileName).put("mimeType", "image/jpeg")
        )
        val sessionId = session.getString("sessionId")
        var driveFileId: String? = null
        file.inputStream().use { input ->
            var offset = 0L
            val total = file.length()
            while (offset < total) {
                val chunk = ByteArray(minOf(CHUNK_SIZE.toLong(), total - offset).toInt())
                var filled = 0
                while (filled < chunk.size) {
                    val count = input.read(chunk, filled, chunk.size - filled)
                    if (count < 0) break
                    filled += count
                }
                if (filled == 0) throw IllegalStateException("Could not read photo data")
                val payload = if (filled == chunk.size) chunk else chunk.copyOf(filled)
                val end = offset + filled - 1
                val (status, bytes) = request(
                    "/v1/photos/upload-sessions/$sessionId",
                    "PUT",
                    deviceToken,
                    payload,
                    "image/jpeg",
                    "bytes $offset-$end/$total"
                )
                if (status == 200 || status == 201) {
                    val driveResult = JSONObject(String(bytes, Charsets.UTF_8))
                    driveFileId = driveResult.optString("id").takeIf { it.isNotBlank() }
                } else if (status != 308) {
                    val error = if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)).optString("error") else ""
                    throw IllegalStateException(error.ifBlank { "Drive upload failed ($status)" })
                }
                offset += filled
            }
        }
        val id = driveFileId ?: throw IllegalStateException("Drive did not confirm the uploaded file")
        jsonRequest(
            "/v1/photos/upload-sessions/$sessionId/complete",
            "POST",
            deviceToken,
            JSONObject().put("driveFileId", id).put("capturedAt", capturedAt)
        )
    }
}
