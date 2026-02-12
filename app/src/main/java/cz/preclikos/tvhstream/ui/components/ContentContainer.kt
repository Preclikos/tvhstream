package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun ContentContainer(
    contentFocus: FocusRequester,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .focusable()
            .focusRequester(contentFocus)
    ) {
        content()
    }
}