/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import androidx.compose.runtime.Stable
import io.element.android.features.messages.impl.messagecomposer.gif.TenorGif
import io.element.android.libraries.textcomposer.mentions.ResolvedSuggestion
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.ImmutableList

enum class TenorPickerTab {
    Gifs,
    Stickers,
    Favorites,
}

@Stable
data class MessageComposerState(
    val textEditorState: TextEditorState,
    val isFullScreen: Boolean,
    val mode: MessageComposerMode,
    val showAttachmentSourcePicker: Boolean,
    val showGifPicker: Boolean,
    val gifQuery: String,
    val selectedTenorTab: TenorPickerTab,
    val gifResults: ImmutableList<TenorGif>,
    val recentGifs: ImmutableList<TenorGif>,
    val favoriteStickers: ImmutableList<TenorGif>,
    val isLoadingGifs: Boolean,
    val showTextFormatting: Boolean,
    val canShareLocation: Boolean,
    val suggestions: ImmutableList<ResolvedSuggestion>,
    val resolveMentionDisplay: (String, String) -> TextDisplay,
    val resolveAtRoomMentionDisplay: () -> TextDisplay,
    val eventSink: (MessageComposerEvent) -> Unit,
)
