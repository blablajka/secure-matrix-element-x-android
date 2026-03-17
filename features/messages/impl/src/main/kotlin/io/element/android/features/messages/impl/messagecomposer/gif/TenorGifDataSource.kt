/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer.gif

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class TenorMediaKind {
    Gif,
    Sticker,
}

data class TenorGif(
    val id: String,
    val title: String,
    val kind: TenorMediaKind,
    val previewUrl: String,
    val mediaUrl: String,
)

data class ImportedTenorMedia(
    val uri: Uri,
    val mimeType: String,
)

interface TenorGifDataSource {
    suspend fun getTrending(sessionId: String, kind: TenorMediaKind, limit: Int = 24): Result<List<TenorGif>>
    suspend fun search(sessionId: String, kind: TenorMediaKind, query: String, limit: Int = 24): Result<List<TenorGif>>
    suspend fun importMedia(sessionId: String, gif: TenorGif): Result<ImportedTenorMedia>
    suspend fun getRecent(): List<TenorGif>
    suspend fun saveRecent(gif: TenorGif)
    suspend fun getFavoriteStickers(sessionId: String): Result<List<TenorGif>>
    suspend fun toggleFavoriteSticker(sessionId: String, gif: TenorGif): Result<List<TenorGif>>
}

@ContributesBinding(AppScope::class)
@Inject
class DefaultTenorGifDataSource(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val okHttpClient: Provider<OkHttpClient>,
) : TenorGifDataSource {
    private val recentLimit = 20

    override suspend fun getTrending(sessionId: String, kind: TenorMediaKind, limit: Int): Result<List<TenorGif>> {
        val sessionData = getSessionData(sessionId)
        val url = buildUrl(
            sessionData = sessionData,
            path = "/_synapse/client/localmedia/tenor/trending",
            queryParams = mapOf(
                "kind" to kind.apiValue,
                "limit" to limit.toString(),
            )
        )
        return fetchTenorItems(sessionData, url)
    }

    override suspend fun search(sessionId: String, kind: TenorMediaKind, query: String, limit: Int): Result<List<TenorGif>> {
        val sessionData = getSessionData(sessionId)
        val url = buildUrl(
            sessionData = sessionData,
            path = "/_synapse/client/localmedia/tenor/search",
            queryParams = mapOf(
                "kind" to kind.apiValue,
                "limit" to limit.toString(),
                "q" to query,
            )
        )
        return fetchTenorItems(sessionData, url)
    }

    override suspend fun importMedia(sessionId: String, gif: TenorGif): Result<ImportedTenorMedia> = runCatching {
        val sessionData = getSessionData(sessionId)
        val importUrl = buildUrl(
            sessionData = sessionData,
            path = "/_synapse/client/localmedia/tenor/import",
        )
        val requestBody = JSONObject().apply {
            put("media_url", gif.mediaUrl)
            put("title", gif.safeTitle())
        }
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = importUrl,
            method = "POST",
            body = requestBody.toString(),
        )
        val downloadUrl = responseJson.optString("download_url")
        val mimeType = responseJson.optString("mime_type").ifBlank { defaultMimeType(gif.kind) }
        if (downloadUrl.isBlank()) {
            throw IOException("Missing download_url in tenor import response")
        }
        ImportedTenorMedia(
            uri = downloadToCache(
                sessionData = sessionData,
                url = downloadUrl,
                mimeType = mimeType,
                filePrefix = "tenor_${gif.kind.apiValue}_",
            ),
            mimeType = mimeType,
        )
    }

    override suspend fun getRecent(): List<TenorGif> {
        val file = recentFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val payload = file.readText()
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val title = item.optString("title")
                    val kind = item.optString("kind").toMediaKind() ?: TenorMediaKind.Gif
                    val previewUrl = item.optString("previewUrl")
                    val mediaUrl = item.optString("mediaUrl")
                    if (id.isBlank() || previewUrl.isBlank() || mediaUrl.isBlank()) continue
                    add(
                        TenorGif(
                            id = id,
                            title = title,
                            kind = kind,
                            previewUrl = previewUrl,
                            mediaUrl = mediaUrl,
                        )
                    )
                }
            }
        }.getOrElse {
            Timber.w(it, "Failed to load recent tenor gifs")
            emptyList()
        }
    }

    override suspend fun saveRecent(gif: TenorGif) {
        val existing = getRecent().filterNot { it.id == gif.id }
        val updated = listOf(gif) + existing
        val payload = JSONArray()
        updated.take(recentLimit).forEach { item ->
            payload.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("kind", item.kind.apiValue)
                    put("previewUrl", item.previewUrl)
                    put("mediaUrl", item.mediaUrl)
                }
            )
        }
        runCatching {
            recentFile().writeText(payload.toString())
        }.onFailure {
            Timber.w(it, "Failed to save recent tenor gifs")
        }
    }

    override suspend fun getFavoriteStickers(sessionId: String): Result<List<TenorGif>> = runCatching {
        val sessionData = getSessionData(sessionId)
        val request = Request.Builder()
            .url(favoritesUrl(sessionData))
            .header("Authorization", "Bearer ${sessionData.accessToken}")
            .get()
            .build()
        okHttpClient().newCall(request).execute().use { response ->
            when {
                response.code == 404 -> emptyList()
                !response.isSuccessful -> throw IOException("Failed to load sticker favorites. code=${response.code}")
                else -> parseFavoriteItems(response.body?.string().orEmpty())
            }
        }
    }

    override suspend fun toggleFavoriteSticker(sessionId: String, gif: TenorGif): Result<List<TenorGif>> = runCatching {
        require(gif.kind == TenorMediaKind.Sticker) { "Only stickers can be favorited" }
        val sessionData = getSessionData(sessionId)
        val existing = getFavoriteStickers(sessionId).getOrThrow()
        val updated = if (existing.any { it.id == gif.id }) {
            existing.filterNot { it.id == gif.id }
        } else {
            listOf(gif) + existing.filterNot { it.id == gif.id }
        }.take(recentLimit)

        val payload = JSONObject().apply {
            put("items", JSONArray().apply {
                updated.forEach { item ->
                    put(item.toJson())
                }
            })
        }
        executeJsonRequest(
            sessionData = sessionData,
            url = favoritesUrl(sessionData),
            method = "PUT",
            body = payload.toString(),
        )
        updated
    }

    private suspend fun fetchTenorItems(sessionData: SessionData, url: String): Result<List<TenorGif>> = runCatching {
        parseTenorItems(
            executeJsonRequest(
                sessionData = sessionData,
                url = url,
                method = "GET",
            )
        )
    }

    private fun parseTenorItems(root: JSONObject): List<TenorGif> {
        val results = root.optJSONArray("items") ?: return emptyList()
        return parseItems(results)
    }

    private fun parseFavoriteItems(raw: String): List<TenorGif> {
        val root = JSONObject(raw)
        val results = root.optJSONArray("items") ?: return emptyList()
        return parseItems(results)
    }

    private fun parseItems(results: JSONArray): List<TenorGif> {
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                val kind = item.optString("kind").toMediaKind() ?: TenorMediaKind.Gif
                val previewUrl = item.optString("preview_url").ifBlank { item.optString("previewUrl") }
                val mediaUrl = item.optString("media_url").ifBlank { item.optString("mediaUrl") }
                if (id.isBlank() || previewUrl.isBlank() || mediaUrl.isBlank()) continue
                add(
                    TenorGif(
                        id = id,
                        title = title,
                        kind = kind,
                        previewUrl = previewUrl,
                        mediaUrl = mediaUrl,
                    )
                )
            }
        }
    }

    private suspend fun getSessionData(sessionId: String): SessionData {
        return sessionStore.getSession(sessionId)
            ?: throw IOException("Missing session data for $sessionId")
    }

    private fun buildUrl(
        sessionData: SessionData,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): String {
        val base = "${sessionData.homeserverUrl.trimEnd('/')}$path"
        if (queryParams.isEmpty()) return base
        val encoded = queryParams.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return "$base?$encoded"
    }

    private suspend fun executeJsonRequest(
        sessionData: SessionData,
        url: String,
        method: String,
        body: String? = null,
    ): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${sessionData.accessToken}")
            .header("Accept", "application/json")

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMediaType))
            "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonMediaType))
            else -> error("Unsupported method: $method")
        }

        okHttpClient().newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Homeserver request failed. code=${response.code}")
            }
            val rawBody = response.body?.string().orEmpty()
            return if (rawBody.isBlank()) JSONObject() else JSONObject(rawBody)
        }
    }

    private suspend fun downloadToCache(
        sessionData: SessionData,
        url: String,
        mimeType: String,
        filePrefix: String,
    ): Uri {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${sessionData.accessToken}")
            .build()
        okHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unable to download imported tenor media. code=${response.code}")
            }
            val body = response.body ?: throw IOException("Empty imported tenor media response body")
            val file = File.createTempFile(filePrefix, extensionForMimeType(mimeType), context.cacheDir)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return Uri.fromFile(file)
        }
    }

    private fun favoritesUrl(sessionData: SessionData): String {
        val encodedUserId = sessionData.userId.urlEncode()
        return buildUrl(
            sessionData = sessionData,
            path = "/_matrix/client/v3/user/$encodedUserId/account_data/$favoriteStickerEventType",
        )
    }

    private fun recentFile() = File(context.filesDir, "recent_tenor_gifs.json")

    private companion object {
        const val favoriteStickerEventType = "io.element.tenor_sticker_favorites"
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        fun extensionForMimeType(mimeType: String): String = when (mimeType) {
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/png" -> ".png"
            else -> ".bin"
        }

        fun defaultMimeType(kind: TenorMediaKind): String = when (kind) {
            TenorMediaKind.Gif -> "image/gif"
            TenorMediaKind.Sticker -> "image/webp"
        }
    }
}

private val TenorMediaKind.apiValue: String
    get() = when (this) {
        TenorMediaKind.Gif -> "gif"
        TenorMediaKind.Sticker -> "sticker"
    }

private fun String.toMediaKind(): TenorMediaKind? = when (this.lowercase()) {
    "gif" -> TenorMediaKind.Gif
    "sticker" -> TenorMediaKind.Sticker
    else -> null
}

private fun TenorGif.safeTitle(): String {
    return title.ifBlank { "${kind.apiValue}_$id" }
}

private fun TenorGif.toJson(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("title", title)
        put("kind", kind.apiValue)
        put("previewUrl", previewUrl)
        put("mediaUrl", mediaUrl)
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
