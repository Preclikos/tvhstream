package cz.preclikos.tvhstream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R

/** Small top-right badge while a channel number is being typed on the remote. */
@Composable
fun ChannelNumberBadge(
    digits: String,
    channelName: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .widthIn(min = 96.dp, max = 360.dp)
            .testTag("channelNumberBadge"),
    ) {
        Text(
            text = digits,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("channelNumberDigits"),
        )
        Text(
            text = channelName ?: stringResource(R.string.channel_number_none),
            color = if (channelName != null) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("channelNumberName"),
        )
    }
}
