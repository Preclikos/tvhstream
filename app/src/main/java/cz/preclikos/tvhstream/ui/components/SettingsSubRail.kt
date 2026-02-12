package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.models.RailItem
import cz.preclikos.tvhstream.ui.screens.SettingsRoutes

@Composable
fun SettingsSubRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    var railFocused by remember { mutableStateOf(false) }

    val items = remember {
        listOf(
            RailItem(SettingsRoutes.CONNECTION, "Connection") {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.White)
            },
            RailItem(SettingsRoutes.PLAYER, "Player") {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
            }
        )
    }

    var didInit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didInit) return@LaunchedEffect
        didInit = true
        railFocusRequester.requestFocus()
    }

    val itemFocus = remember(items) { items.associate { it.route to FocusRequester() } }

    LaunchedEffect(railFocused, currentRoute) {
        if (!railFocused) return@LaunchedEffect
        val target = itemFocus[currentRoute] ?: itemFocus[items.first().route]
        target?.requestFocus()
    }

    Column(
        modifier
            .focusRequester(railFocusRequester)
            .width(180.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .onFocusChanged { railFocused = it.hasFocus }
            .focusable()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        items.forEach { item ->
            val selected = currentRoute == item.route
            SettingsSubRailItem(
                selected = selected,
                label = item.label,
                icon = item.icon,
                focusRequester = itemFocus.getValue(item.route),
                onClick = {
                    railFocused = false
                    onNavigate(item.route)
                }
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SettingsSubRailItem(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    val bg = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
        else -> Color.Transparent
    }

    val borderColor =
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else Color.Transparent

    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .height(44.dp) // smaller row height
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key == Key.Enter ||
                            ev.key == Key.NumPadEnter ||
                            ev.key == Key.DirectionCenter //||
                            //ev.key == Key.DirectionRight ||
                            //ev.key == Key.ButtonThumbRight
                            )
                ) {
                    onClick(); true
                } else false
            }
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { // smaller icon box
            icon()
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge, // smaller text than titleSmall
            color = Color.White,
            maxLines = 1
        )
    }
}
