package cz.preclikos.tvhstream.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel

@OptIn(UnstableApi::class)
@Composable
fun ChannelsScreen(
    vm: AppConnectionViewModel,
    onPlay: (channelId: Int, serviceId: Int, channelName: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val channels by vm.channels.collectAsState()
    val status by vm.status.collectAsState()

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            kotlinx.coroutines.delay(5000L)
        }
    }

    Column(Modifier
        .fillMaxSize()
        .padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TVH Client", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onOpenSettings() }) { Text("Settings") }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))
        Text("Channels (${channels.size})", style = MaterialTheme.typography.titleMedium)

        val listState = rememberLazyListState()

        LazyColumn(state = listState) {
            items(channels) { ch ->
                // ⚠️ zatím hack: channelId==serviceId==ch.id
                val now = remember(ch.id, nowSec) { vm.nowEvent(ch.id, nowSec) }
                val progress = remember(now, nowSec) { now?.progress(nowSec) ?: 0f }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPlay(
                                /*channelId=*/ ch.id,
                                /*serviceId=*/ ch.id,
                                /*name=*/ ch.name
                            )
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, maxLines = 1)
                            Text(
                                now?.title ?: "No EPG",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        Text("▶", style = MaterialTheme.typography.labelLarge)
                    }

                    if (now != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(top = 6.dp)
                        )
                    }
                }

                HorizontalDivider()
            }
        }
    }
}
