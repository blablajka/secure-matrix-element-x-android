/*
 * Copyright (c) 2026 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.localdm

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
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalDmApiTest {
    private val sessionId = "@alice:chat.test"
    private val accessToken = "super-secret-token"

    @Test
    fun `lookup uses authenticated mailbox contract`() = runTest {
        val interceptor = RecordingInterceptor()
        val api = createApi(interceptor)

        val result = api.lookupMailboxes(sessionId, "@bob:chat.test").getOrThrow()

        assertThat(result.mailboxes).hasSize(1)
        assertThat(result.mailboxes.single().mailboxId).isEqualTo("mailbox-bob-1")

        val request = interceptor.recordedRequests.single { it.url.contains("/mailbox_lookup") }
        assertThat(request.authorization).isEqualTo("Bearer $accessToken")
        assertThat(request.url).contains("user_id=%40bob%3Achat.test")
    }

    @Test
    fun `send uses mailbox routed payload only`() = runTest {
        val interceptor = RecordingInterceptor()
        val api = createApi(interceptor)

        val result = api.send(
            sessionId = sessionId,
            request = LocalDmSendRequest(
                recipientMailboxId = "mailbox-bob-1",
                envelopeCiphertext = "opaque-envelope",
                paddedSizeBucket = 64,
            ),
        ).getOrThrow()

        assertThat(result.cursor).isEqualTo(42)

        val request = interceptor.recordedRequests.single { it.url.contains("/localdm/send") }
        assertThat(request.body).contains("\"recipient_mailbox_id\":\"mailbox-bob-1\"")
        assertThat(request.body).contains("\"envelope_ciphertext\":\"opaque-envelope\"")
        assertThat(request.body).contains("\"padded_size_bucket\":64")
        assertThat(request.body).doesNotContain("recipient_user_id")
        assertThat(request.body).doesNotContain("conversation_token")
        assertThat(request.body).doesNotContain("content_type")
    }

    @Test
    fun `sync parses minimal envelope shape`() = runTest {
        val api = createApi(RecordingInterceptor())

        val result = api.sync(sessionId, fromCursor = 0, limit = 20).getOrThrow()

        assertThat(result.nextCursor).isEqualTo(42)
        assertThat(result.envelopes).hasSize(1)
        assertThat(result.envelopes.single().cursor).isEqualTo(42)
        assertThat(result.envelopes.single().paddedSizeBucket).isEqualTo(64)
    }

    private fun createApi(interceptor: RecordingInterceptor): DefaultLocalDmApi {
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
        return DefaultLocalDmApi(
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
                request.url.encodedPath.endsWith("/_synapse/client/localdm/mailbox_lookup") -> {
                    request.jsonResponse(
                        """
                        {
                          "mailboxes": [
                            {
                              "mailbox_id": "mailbox-bob-1",
                              "key_bundle": {"curve25519":"bob-key"},
                              "fallback_key_bundle": {"curve25519":"bob-fallback"},
                              "key_version": 7,
                              "state": "active"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }

                request.url.encodedPath.endsWith("/_synapse/client/localdm/send") -> {
                    request.jsonResponse("""{"cursor":42}""")
                }

                request.url.encodedPath.endsWith("/_synapse/client/localdm/sync") -> {
                    request.jsonResponse(
                        """
                        {
                          "next_cursor": 42,
                          "envelopes": [
                            {
                              "cursor": 42,
                              "envelope_ciphertext": "opaque-envelope",
                              "padded_size_bucket": 64
                            }
                          ]
                        }
                        """.trimIndent()
                    )
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

private fun okhttp3.RequestBody?.readUtf8(): String {
    if (this == null) return ""
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
