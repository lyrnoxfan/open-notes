package com.example.privatenotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Read-only tag labels under a note entry (§4.3). */
@Composable
fun TagLabelRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    val shown = tags.take(MAX_LABELS)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                // TalkBack reads one clean phrase instead of spelling out each "#tag".
                contentDescription = "标签：" + shown.joinToString("、")
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { tag ->
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Horizontally scrollable tag filter (§4.3, §7.3).
 *
 * Chips are quiet by default: a hairline outline on the page background, no fill. Only the tag
 * that is actually filtering the list is filled, so the row reads as a filter state rather than
 * a row of buttons.
 */
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
    ) {
        items(tags, key = { it }) { tag ->
            val selected = tag == selectedTag
            FilterChip(
                selected = selected,
                onClick = { onSelect(tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.primaryContainer,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

private const val MAX_LABELS = 4
