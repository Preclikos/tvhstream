package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun RoundIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = if (focused) {
        Color.White.copy(alpha = 0.26f)   // fokus výraznější, ale decentní
    } else {
        Color.White.copy(alpha = 0.14f)
    }

    Surface(
        color = Color.Transparent,
        contentColor = Color.White,
        modifier = Modifier
            .size(46.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { st ->
                focused = st.isFocused
                if (st.isFocused) onFocused()
            }
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            icon()
        }
    }
}