/*
 * Copyright (c) 2026 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.localdm

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class LocalDmMailboxState(
    val mailboxId: String,
    val keyBundle: JSONObject,
    val fallbackKeyBundle: JSONObject?,
    val keyVersion: Int,
    val state: String,
)

data class LocalDmMailboxRegistration(
    val mailboxId: String,
    val keyBundle: JSONObject,
    val fallbackKeyBundle: JSONObject? = null,
    val replacesMailboxId: String? = null,
)

data class LocalDmLookupResult(
    val mailboxes: List<LocalDmMailboxState>,
)

data class LocalDmSendRequest(
    val recipientMailboxId: String,
    val envelopeCiphertext: String,
    val paddedSizeBucket: Int? = null,
    val expiresInMs: Int? = null,
)

data class LocalDmSendResult(
    val cursor: Long,
)

data class LocalDmEnvelope(
    val cursor: Long,
    val envelopeCiphertext: String,
    val paddedSizeBucket: Int,
)

data class LocalDmSyncResult(
    val nextCursor: Long,
    val envelopes: List<LocalDmEnvelope>,
)

interface LocalDmApi {
    suspend fun getMailbox(sessionId: String): Result<LocalDmMailboxState>
    suspend fun putMailbox(sessionId: String, registration: LocalDmMailboxRegistration): Result<LocalDmMailboxState>
    suspend fun lookupMailboxes(sessionId: String, userId: String): Result<LocalDmLookupResult>
    suspend fun send(sessionId: String, request: LocalDmSendRequest): Result<LocalDmSendResult>
    suspend fun sync(sessionId: String, fromCursor: Long, limit: Int = 100): Result<LocalDmSyncResult>
    suspend fun acknowledge(sessionId: String, cursors: List<Long>): Result<Int>
}

@ContributesBinding(AppScope::class)
@Inject
class DefaultLocalDmApi(
    private val sessionStore: SessionStore,
    private val okHttpClient: Provider<OkHttpClient>,
) : LocalDmApi {
    override suspend fun getMailbox(sessionId: String): Result<LocalDmMailboxState> = runCatching {
        val sessionData = getSessionData(sessionId)
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(sessionData, "/_synapse/client/localdm/mailbox"),
            method = "GET",
        )
        responseJson.toMailboxState()
    }

    override suspend fun putMailbox(
        sessionId: String,
        registration: LocalDmMailboxRegistration,
    ): Result<LocalDmMailboxState> = runCatching {
        val sessionData = getSessionData(sessionId)
        val body = JSONObject().apply {
            put("mailbox_id", registration.mailboxId)
            put("key_bundle", registration.keyBundle)
            registration.fallbackKeyBundle?.let { put("fallback_key_bundle", it) }
            registration.replacesMailboxId?.let { put("replaces_mailbox_id", it) }
        }
        executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(sessionData, "/_synapse/client/localdm/mailbox"),
            method = "PUT",
            body = body.toString(),
        ).toMailboxState()
    }

    override suspend fun lookupMailboxes(sessionId: String, userId: String): Result<LocalDmLookupResult> = runCatching {
        val sessionData = getSessionData(sessionId)
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(
                sessionData = sessionData,
                path = "/_synapse/client/localdm/mailbox_lookup",
                queryParams = mapOf("user_id" to userId),
            ),
            method = "GET",
        )
        val mailboxes = responseJson.getJSONArray("mailboxes")
        LocalDmLookupResult(
            mailboxes = buildList {
                for (index in 0 until mailboxes.length()) {
                    val mailbox = mailboxes.getJSONObject(index)
                    add(mailbox.toMailboxState())
                }
            }
        )
    }

    override suspend fun send(sessionId: String, request: LocalDmSendRequest): Result<LocalDmSendResult> = runCatching {
        val sessionData = getSessionData(sessionId)
        val body = JSONObject().apply {
            put("recipient_mailbox_id", request.recipientMailboxId)
            put("envelope_ciphertext", request.envelopeCiphertext)
            request.paddedSizeBucket?.let { put("padded_size_bucket", it) }
            request.expiresInMs?.let { put("expires_in_ms", it) }
        }
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(sessionData, "/_synapse/client/localdm/send"),
            method = "POST",
            body = body.toString(),
        )
        LocalDmSendResult(cursor = responseJson.getLong("cursor"))
    }

    override suspend fun sync(sessionId: String, fromCursor: Long, limit: Int): Result<LocalDmSyncResult> = runCatching {
        val sessionData = getSessionData(sessionId)
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(
                sessionData = sessionData,
                path = "/_synapse/client/localdm/sync",
                queryParams = mapOf(
                    "from" to fromCursor.toString(),
                    "limit" to limit.toString(),
                ),
            ),
            method = "GET",
        )
        val envelopes = responseJson.getJSONArray("envelopes")
        LocalDmSyncResult(
            nextCursor = responseJson.getLong("next_cursor"),
            envelopes = buildList {
                for (index in 0 until envelopes.length()) {
                    val envelope = envelopes.getJSONObject(index)
                    add(
                        LocalDmEnvelope(
                            cursor = envelope.getLong("cursor"),
                            envelopeCiphertext = envelope.getString("envelope_ciphertext"),
                            paddedSizeBucket = envelope.getInt("padded_size_bucket"),
                        )
                    )
                }
            }
        )
    }

    override suspend fun acknowledge(sessionId: String, cursors: List<Long>): Result<Int> = runCatching {
        val sessionData = getSessionData(sessionId)
        val body = JSONObject().apply {
            put("cursors", JSONArray().apply { cursors.forEach(::put) })
        }
        val responseJson = executeJsonRequest(
            sessionData = sessionData,
            url = buildUrl(sessionData, "/_synapse/client/localdm/ack"),
            method = "POST",
            body = body.toString(),
        )
        responseJson.getInt("deleted_count")
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

    private companion object {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JSONObject.toMailboxState(): LocalDmMailboxState {
    return LocalDmMailboxState(
        mailboxId = getString("mailbox_id"),
        keyBundle = getJSONObject("key_bundle"),
        fallbackKeyBundle = optJSONObject("fallback_key_bundle"),
        keyVersion = getInt("key_version"),
        state = getString("state"),
    )
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
