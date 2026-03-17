/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.messagecomposer.gif

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TenorGifDataSourceTest {
    private val sessionId = "@alice:chat.test"
    private val accessToken = "super-secret-token"

    @Test
    fun `search returns opaque media reference from homeserver`() = runTest {
        val interceptor = RecordingInterceptor()
        val dataSource = createDataSource(interceptor)

        val result = dataSource.search(
            sessionId = sessionId,
            kind = TenorMediaKind.Gif,
            query = "hello",
        ).getOrThrow()

        assertThat(result).hasSize(1)
        assertThat(result.single().previewUrl).isEqualTo(
            "https://chat.test/_synapse/client/localmedia/tenor/proxy?token=preview-token"
        )
        assertThat(result.single().mediaUrl).isEqualTo("opaque-media-token")

        val request = interceptor.recordedRequests.single { it.url.contains("/tenor/search") }
        assertThat(request.authorization).isEqualTo("Bearer $accessToken")
        assertThat(request.url).contains("kind=gif")
        assertThat(request.url).contains("q=hello")
    }

    @Test
    fun `import uses homeserver token and downloads local media`() = runTest {
        val interceptor = RecordingInterceptor()
        val dataSource = createDataSource(interceptor)

        val imported = dataSource.importMedia(
            sessionId = sessionId,
            gif = TenorGif(
                id = "tenor-1",
                title = "hello.gif",
                kind = TenorMediaKind.Gif,
                previewUrl = "https://chat.test/_synapse/client/localmedia/tenor/proxy?token=preview-token",
                mediaUrl = "opaque-media-token",
            ),
        ).getOrThrow()

        assertThat(imported.mimeType).isEqualTo("image/gif")
        assertThat(imported.uri.toString()).startsWith("file:")

        val importRequest = interceptor.recordedRequests.single { it.url.contains("/tenor/import") }
        assertThat(importRequest.authorization).isEqualTo("Bearer $accessToken")
        assertThat(importRequest.body).contains("\"media_url\":\"opaque-media-token\"")
        assertThat(importRequest.body).doesNotContain("media.tenor.com")

        val downloadRequest = interceptor.recordedRequests.single { it.url.contains("/media/download/") }
        assertThat(downloadRequest.authorization).isEqualTo("Bearer $accessToken")
        assertThat(downloadRequest.url).isEqualTo(
            "https://chat.test/_matrix/client/v1/media/download/test/mediaid/hello.gif"
        )
    }

    @Test
    fun `save recent preserves opaque media token`() = runTest {
        val dataSource = createDataSource(RecordingInterceptor())
        val gif = TenorGif(
            id = "tenor-1",
            title = "hello.gif",
            kind = TenorMediaKind.Gif,
            previewUrl = "https://chat.test/_synapse/client/localmedia/tenor/proxy?token=preview-token",
            mediaUrl = "opaque-media-token",
        )

        dataSource.saveRecent(gif)

        val recent = dataSource.getRecent()
        assertThat(recent).isNotEmpty()
        assertThat(recent.first().mediaUrl).isEqualTo("opaque-media-token")
        assertThat(recent.first().previewUrl).contains("token=preview-token")
    }

    private fun createDataSource(interceptor: RecordingInterceptor): DefaultTenorGifDataSource {
        val context = RuntimeEnvironment.getApplication() as Context
        context.filesDir.resolve("recent_tenor_gifs.json").delete()
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(sessionId = sessionId).copy(
                    accessToken = accessToken,
                    homeserverUrl = "https://chat.test",
                )
            )
        )
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        return DefaultTenorGifDataSource(
            context = context,
            sessionStore = sessionStore,
            okHttpClient = Provider { okHttpClient },
        )
    }

    private data class RecordedRequest(
        val url: String,
        val authorization: String?,
        val body: String,
    )

    private class RecordingInterceptor : Interceptor {
        val recordedRequests = mutableListOf<RecordedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            recordedRequests += RecordedRequest(
                url = request.url.toString(),
                authorization = request.header("Authorization"),
                body = request.body.readUtf8(),
            )

            return when {
                request.url.encodedPath.endsWith("/_synapse/client/localmedia/tenor/search") -> {
                    request.jsonResponse(
                        """
                        {
                          "items": [
                            {
                              "id": "tenor-1",
                              "title": "wave",
                              "kind": "gif",
                              "preview_url": "https://chat.test/_synapse/client/localmedia/tenor/proxy?token=preview-token",
                              "media_url": "opaque-media-token"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }

                request.url.encodedPath.endsWith("/_synapse/client/localmedia/tenor/import") -> {
                    request.jsonResponse(
                        """
                        {
                          "download_url": "https://chat.test/_matrix/client/v1/media/download/test/mediaid/hello.gif",
                          "mime_type": "image/gif"
                        }
                        """.trimIndent()
                    )
                }

                request.url.encodedPath.contains("/_matrix/client/v1/media/download/") -> {
                    request.binaryResponse("GIF89a".encodeToByteArray(), "image/gif")
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
    }
}

private fun okhttp3.Request.jsonResponse(body: String): Response {
    return Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
        .build()
}

private fun okhttp3.Request.binaryResponse(body: ByteArray, contentType: String): Response {
    return Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()
}

private fun okhttp3.RequestBody?.readUtf8(): String {
    if (this == null) return ""
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
