package dev.brahmkshatriya.echo.extension

import android.annotation.SuppressLint
import android.app.Application
import com.luftnos.unplayplay.PCdm.playPlayRequest
import com.luftnos.unplayplay.PCdm.playPlayResponse
import com.luftnos.unplayplay.Unplayplay
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.spotify.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File


@Suppress("unused")
class ADSpotifyExtension : SpotifyExtension() {

    @SuppressLint("PrivateApi")
    private fun getApplication(): Application {
        return Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as Application
    }

    override val filesDir by lazy { File(getApplication().filesDir, "spotify") }
    override val showWidevineStreams = true

    private val client = OkHttpClient.Builder().build()

    override suspend fun getKeyWithPlayPlay(json: Json, accessToken: String, fileId: String): ByteArray {
        return withContext(Dispatchers.IO) {
            // Retry logic for PlayPlay/unplayplay server (up to 3 attempts with exponential backoff)
            var lastException: Exception? = null
            repeat(3) { attempt ->
                try {
                    val request = Request.Builder()
                    request.url(Unplayplay.getPlayPlayUrl(fileId))
                    request.addHeader("Authorization", "Bearer $accessToken")
                    request.post(playPlayRequest(Unplayplay.token).toRequestBody())
                    val raw = client.newCall(request.build()).await()
                    if (!raw.isSuccessful) {
                        throw Exception("Error ${raw.code}: ${raw.message}")
                    }
                    val hex = playPlayResponse(raw.body.bytes()).toHexString()
                    val req = Request.Builder().url("https://another-unplayplay-server.fly.dev/getAesKey")
                        .post(json.encode(buildJsonObject {
                            put("obfuscatedKey", hex)
                            put("encoding", "hex")
                        }).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    val jsonReq = client.newCall(req).execute()
                    val body = jsonReq.body.string()
                    val key = json.decode<JsonObject>(body).jsonObject["aesKey"]?.jsonPrimitive?.content
                        ?: throw Exception("Couldn't get decryption key")
                    return@withContext key.hexToBytes()
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 2) {
                        // Exponential backoff: 1s, 2s
                        delay((attempt + 1) * 1000L)
                    }
                }
            }
            throw lastException ?: Exception("Failed to get decryption key after retries")
        }
    }

    fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}