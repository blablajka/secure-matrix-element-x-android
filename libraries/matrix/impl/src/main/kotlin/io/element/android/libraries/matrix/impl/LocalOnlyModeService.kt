/*
 * Copyright (c) 2026 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.LocalOnlyModeService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.sessionstorage.api.SessionStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.json.JSONObject

@ContributesBinding(SessionScope::class)
@Inject
class DefaultLocalOnlyModeService(
    private val matrixClient: MatrixClient,
    private val sessionStore: SessionStore,
    private val okHttpClient: Provider<OkHttpClient>,
) : LocalOnlyModeService {
    override suspend fun isLocalOnly(): Result<Boolean> = runCatching {
        val sessionData = sessionStore.getSession(matrixClient.sessionId.value)
            ?: throw IOException("Missing session data for ${matrixClient.sessionId.value}")
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
