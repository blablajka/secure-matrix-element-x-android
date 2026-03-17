/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import coil3.compose.AsyncImage
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.messagecomposer.gif.TenorGif
import io.element.android.features.messages.impl.messagecomposer.gif.TenorMediaKind
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GifPickerBottomSheet(
    state: MessageComposerState,
    modifier: Modifier = Modifier,
) {
    var isVisible by rememberSaveable { mutableStateOf(state.showGifPicker) }

    BackHandler(enabled = isVisible) {
        isVisible = false
    }

    if (!state.showGifPicker && isVisible) {
        isVisible = false
    } else if (state.showGifPicker && !isVisible) {
        isVisible = true
    }

    if (!isVisible) return

    ModalBottomSheet(
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = {
            isVisible = false
            state.eventSink(MessageComposerEvent.DismissGifMenu)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TenorPickerTab.values().forEach { tab ->
                    FilterChip(
                        selected = state.selectedTenorTab == tab,
                        onClick = { state.eventSink(MessageComposerEvent.UpdateTenorTab(tab)) },
                        label = { Text(text = tab.title()) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.gifQuery,
                    onValueChange = { state.eventSink(MessageComposerEvent.UpdateGifQuery(it)) },
                    modifier = Modifier.fillMaxWidth(0.76f),
                    singleLine = true,
                    label = { Text(state.selectedTenorTab.searchHint()) },
                )
                Button(
                    onClick = { state.eventSink(MessageComposerEvent.SearchGif) },
                ) {
                    Text(stringResource(CommonStrings.action_search))
                }
            }

            if (state.selectedTenorTab == TenorPickerTab.Gifs && state.recentGifs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.screen_room_gif_recent))
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.recentGifs.size) { index ->
                        GifTile(
                            modifier = Modifier.size(96.dp),
                            gif = state.recentGifs[index],
                            onClick = { state.eventSink(MessageComposerEvent.SelectGif(state.recentGifs[index])) },
                            isFavorite = false,
                            onLongClick = null,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state.isLoadingGifs) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            if (!state.isLoadingGifs && state.selectedTenorTab == TenorPickerTab.Favorites && state.gifResults.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.screen_room_tenor_favorites_empty))
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.gifResults) { gif ->
                    val canFavorite = gif.kind == TenorMediaKind.Sticker
                    GifTile(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        gif = gif,
                        onClick = { state.eventSink(MessageComposerEvent.SelectGif(gif)) },
                        isFavorite = state.favoriteStickers.any { it.id == gif.id },
                        onLongClick = if (canFavorite) {
                            { state.eventSink(MessageComposerEvent.ToggleStickerFavorite(gif)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GifTile(
    gif: TenorGif,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        ) {
            AsyncImage(
                model = gif.previewUrl,
                contentDescription = gif.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (gif.kind == TenorMediaKind.Sticker) {
                Icon(
                    imageVector = if (isFavorite) CompoundIcons.FavouriteSolid() else CompoundIcons.Favourite(),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(18.dp),
                )
            }
        }
        Text(
            text = gif.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TenorPickerTab.title(): String = when (this) {
    TenorPickerTab.Gifs -> stringResource(R.string.screen_room_tenor_tab_gifs)
    TenorPickerTab.Stickers -> stringResource(R.string.screen_room_tenor_tab_stickers)
    TenorPickerTab.Favorites -> stringResource(R.string.screen_room_tenor_tab_favorites)
}

@Composable
private fun TenorPickerTab.searchHint(): String = when (this) {
    TenorPickerTab.Gifs -> stringResource(R.string.screen_room_tenor_search_gifs_hint)
    TenorPickerTab.Stickers -> stringResource(R.string.screen_room_tenor_search_stickers_hint)
    TenorPickerTab.Favorites -> stringResource(R.string.screen_room_tenor_search_favorites_hint)
}
