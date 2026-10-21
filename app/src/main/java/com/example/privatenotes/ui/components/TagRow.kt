package com.example.privatenotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Read-only tag labels under a note entry (§4.3). */
@Composable
fun TagLabelRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.take(MAX_LABELS).forEach { tag ->
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Horizontally scrollable tag filter (§4.3, §7.3). */
@Composable
fun TagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
    ) {
        items(tags, key = { it }) { tag ->
            FilterChip(
                selected = tag == selectedTag,
                onClick = { onSelect(tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

private const val MAX_LABELS = 4

/** Keeps the chip row visually aligned with list content. */
val TagRowPadding = Modifier.padding(top = 6.dp)
