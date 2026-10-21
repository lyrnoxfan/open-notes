package com.example.privatenotes.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The widest the content column is allowed to get (design.md §3).
 *
 * On a phone this does nothing: the column is the screen minus its margins. On a tablet or in
 * landscape it keeps a single readable column of text instead of stretching lines across the
 * whole display - the same 680-unit measure the reference site uses.
 */
val ContentMaxWidth = 680.dp

/** Applies the reading measure to a scroll container such as `LazyColumn`. */
fun Modifier.readingWidth(): Modifier = this.widthIn(max = ContentMaxWidth).fillMaxWidth()

/** A vertical stack limited to the reading measure. */
@Composable
fun ReadingColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.readingWidth(), content = content)
    }
}
