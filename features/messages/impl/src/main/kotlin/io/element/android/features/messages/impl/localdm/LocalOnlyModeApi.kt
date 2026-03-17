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
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.json.JSONObject

fun interface LocalOnlyModeApi {
    suspend fun isLocalOnly(sessionId: String): Result<Boolean>
}

@ContributesBinding(AppScope::class)
@Inject
class DefaultLocalOnlyModeApi(
    private val sessionStore: SessionStore,
    private val okHttpClient: Provider<OkHttpClient>,
) : LocalOnlyModeApi {
    override suspend fun isLocalOnly(sessionId: String): Result<Boolean> = runCatching {
        val sessionData = sessionStore.getSession(sessionId)
            ?: throw IOException("Missing session data for $sessionId")
        val request = Request.Builder()
            .url("${sessionData.homeserverUrl.trimEnd('/')}/_matrix/client/versions")
            .header("Authorization", "Bearer ${sessionData.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()

        okHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Homeserver request failed. code=${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                false
            } else {
                JSONObject(body)
                    .optJSONObject("unstable_features")
                    ?.optBoolean("io.element.local_only_mode", false)
                    ?: false
            }
        }
    }
}
