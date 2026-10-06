package br.com.shopeeai.publisher

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID


data class PublisherAd(
    val id: Int,
    val productName: String,
    val copy: String,
    val hashtags: String,
    val affiliateLink: String,
    val videoUrl: String,
    val publishAttempts: Int,
)

class BackendClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("publisher", Context.MODE_PRIVATE)

    private fun base(): String = (prefs.getString("backend", "") ?: "").trim().trimEnd('/')
    private fun token(): String = prefs.getString("token", "") ?: ""
    fun deviceId(): String {
        var value = prefs.getString("device_id", null)
        if (value.isNullOrBlank()) {
            value = "android-" + UUID.randomUUID().toString().take(12)
            prefs.edit().putString("device_id", value).apply()
        }
        return value
    }

    private fun connection(path: String, method: String = "GET"): HttpURLConnection {
        val url = if (path.startsWith("http")) path else base() + path
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("X-Publisher-Token", token())
        c.setRequestProperty("X-Device-Id", deviceId())
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    fun testConnection(): String {
        val c = connection("/health")
        return try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            if (c.responseCode == 200) "Backend conectado: $body" else "HTTP ${c.responseCode}: $body"
        } finally { c.disconnect() }
    }

    fun claimNext(): PublisherAd? {
        val c = connection("/api/publisher/claim-next", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{}".toByteArray()) }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: $body")
            val item = JSONObject(body).optJSONObject("item") ?: return null
            PublisherAd(
                id = item.getInt("id"),
                productName = item.optString("product_name"),
                copy = item.optString("copy"),
                hashtags = item.optString("hashtags"),
                affiliateLink = item.optString("affiliate_link"),
                videoUrl = item.optString("video_url"),
                publishAttempts = item.optInt("publish_attempts", 1),
            )
        } finally { c.disconnect() }
    }

    fun downloadVideo(ad: PublisherAd): Uri {
        val c = connection(ad.videoUrl)
        c.setRequestProperty("Accept", "video/mp4")
        c.connect()
        if (c.responseCode !in 200..299) {
            val body = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            throw IllegalStateException("Falha ao baixar vídeo: HTTP ${c.responseCode} $body")
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ShopeeAI_${ad.id}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShopeeAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível criar vídeo no MediaStore")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                BufferedInputStream(c.inputStream).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Não foi possível gravar o vídeo")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            c.disconnect()
        }
    }

    fun markPublished(id: Int) = postJson("/api/publisher/$id/published", "{}")

    fun markFailed(id: Int, error: String, requeue: Boolean) {
        val obj = JSONObject().put("error", error.take(1500)).put("requeue", requeue)
        postJson("/api/publisher/$id/failed", obj.toString())
    }

    private fun postJson(path: String, body: String) {
        val c = connection(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: $text")
        } finally { c.disconnect() }
    }
}
