package com.dramaflow.app.local

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocalDramaApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetchFeed(
        baseUrl: String,
        region: String,
        onSuccess: (List<LocalDrama>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val url = endpoint(baseUrl, "/v1/feed").toHttpUrl().newBuilder()
            .addQueryParameter("region", region)
            .addQueryParameter("limit", "50")
            .build()
        execute(Request.Builder().url(url).get().build(), onFailure) { json ->
            val items = json.optJSONArray("items")
            onSuccess(buildList {
                if (items != null) for (index in 0 until items.length()) add(parseDrama(items.getJSONObject(index), includeEpisodes = false, baseUrl = baseUrl))
            })
        }
    }

    fun fetchDrama(
        baseUrl: String,
        dramaId: String,
        region: String,
        userId: String,
        onSuccess: (LocalDrama) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val url = endpoint(baseUrl, "/v1/dramas/${encodePathSegment(dramaId)}").toHttpUrl().newBuilder()
            .addQueryParameter("region", region)
            .addQueryParameter("user_id", userId)
            .build()
        execute(Request.Builder().url(url).get().build(), onFailure) { json -> onSuccess(parseDrama(json, includeEpisodes = true, baseUrl = baseUrl)) }
    }

    fun createPlaybackSession(
        baseUrl: String,
        episodeId: String,
        region: String,
        userId: String,
        deviceId: String,
        onSuccess: (LocalPlaybackSession) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val payload = JSONObject()
            .put("region", region)
            .put("user_id", userId)
            .put("device_id", deviceId)
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/v1/episodes/${encodePathSegment(episodeId)}/playback-session"))
            .post(payload.toString().toRequestBody(JSON))
            .build()
        execute(request, onFailure) { json ->
            val streamUrl = json.optString("stream_url")
            if (streamUrl.isBlank()) error("播放会话未返回 stream_url")
            onSuccess(
                LocalPlaybackSession(
                    episodeId = json.optString("episode_id", episodeId),
                    streamUrl = streamUrl,
                    entitlement = json.optString("entitlement"),
                    message = json.optString("message").takeIf { it.isNotBlank() },
                )
            )
        }
    }

    fun unlockEpisode(
        baseUrl: String,
        episodeId: String,
        region: String,
        userId: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val payload = JSONObject()
            .put("region", region)
            .put("method", "AD_REWARD")
            .put("user_id", userId)
            .put("proof", "TEST_REWARD_PROOF")
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/v1/episodes/${encodePathSegment(episodeId)}/unlock"))
            .post(payload.toString().toRequestBody(JSON))
            .build()
        execute(request, onFailure) { json ->
            if (json.optBoolean("unlocked")) onSuccess() else onFailure(json.optString("message", "服务端未确认解锁"))
        }
    }

    private fun execute(
        request: Request,
        onFailure: (String) -> Unit,
        onSuccess: (JSONObject) -> Unit,
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = onFailure(error.message ?: "网络连接失败")

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                        onFailure("HTTP ${it.code}${detail.takeIf(String::isNotBlank)?.let { value -> "：$value" }.orEmpty()}")
                        return
                    }
                    runCatching { JSONObject(body) }
                        .onSuccess(onSuccess)
                        .onFailure { error -> onFailure("响应解析失败：${error.message}") }
                }
            }
        })
    }

    private fun parseDrama(json: JSONObject, includeEpisodes: Boolean, baseUrl: String): LocalDrama {
        val episodes = buildList {
            if (includeEpisodes) {
                val items = json.optJSONArray("episodes")
                if (items != null) for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    add(
                        LocalEpisode(
                            id = item.getString("id"),
                            dramaId = item.optString("drama_id", json.getString("id")),
                            index = item.optInt("index", index + 1),
                            title = item.optString("title", "第 ${index + 1} 集"),
                            durationSeconds = item.optInt("duration_seconds", 1).coerceAtLeast(1),
                            coverUrl = resolveMediaUrl(baseUrl, item.optString("cover_url").ifBlank { item.optString("coverUrl") }),
                            entitlement = item.optString("entitlement", "FREE"),
                        )
                    )
                }
            }
        }
        val genres = json.optJSONArray("genre")
        return LocalDrama(
            id = json.getString("id"),
            title = json.optString("title", "精彩短剧"),
            desc = json.optString("summary"),
            coverImage = resolveMediaUrl(baseUrl, json.optString("cover_url").ifBlank { json.optString("coverUrl") }),
            total = json.optInt("total_episodes", episodes.size.coerceAtLeast(1)).coerceAtLeast(1),
            freeEpisodes = json.optInt("free_episodes", 3).coerceAtLeast(0),
            genres = buildList {
                if (genres != null) for (index in 0 until genres.length()) add(genres.optString(index))
            },
            episodes = episodes,
        )
    }

    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + path

    private fun resolveMediaUrl(baseUrl: String, rawUrl: String): String {
        val value = rawUrl.trim()
        if (value.isBlank() || value.startsWith("http://") || value.startsWith("https://")) return value
        return if (value.startsWith('/')) baseUrl.trimEnd('/') + value else baseUrl.trimEnd('/') + "/" + value
    }

    private fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
