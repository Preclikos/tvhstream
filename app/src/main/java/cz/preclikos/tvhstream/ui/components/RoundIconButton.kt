package cz.preclikos.tvhstream.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
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
    onFocused: () -> Unit,
    contentDescription: String? = null,
) {
    var focused by remember { mutableStateOf(false) }

    // TV focus: inverted (opaque white, dark icon) and slightly enlarged so it is
    // clearly visible over any video frame.
    val bg = if (focused) Color.White else Color.White.copy(alpha = 0.14f)
    val fg = if (focused) Color.Black else Color.White
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "focusScale")

    Surface(
        color = Color.Transparent,
        contentColor = fg,
        modifier = Modifier
            .size(46.dp)
            .scale(scale)
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            }
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