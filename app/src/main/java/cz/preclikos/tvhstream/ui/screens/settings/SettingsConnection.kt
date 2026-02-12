package cz.preclikos.tvhstream.ui.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.ServerSettingsStore
import cz.preclikos.tvhstream.ui.components.TvOutlinedTextField
import cz.preclikos.tvhstream.ui.components.TvPasswordField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingsConnection(
    settingsStore: ServerSettingsStore = koinInject(),
    passwordStore: SecurePasswordStore = koinInject()
) {
    val scope = rememberCoroutineScope()

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    var host by rememberSaveable { mutableStateOf("") }
    var htspPort by rememberSaveable { mutableStateOf("9982") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var auto by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val s = settingsStore.serverSettings.first()
        host = s.host
        htspPort = s.htspPort.toString()
        user = s.username
        pass = passwordStore.getPassword()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_server),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 10.dp)
        )

        Column(modifier = Modifier.focusGroup()) {

            TvOutlinedTextField(
                id = "host",
                editingId = editingId,
                setEditingId = { editingId = it },
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.host)) }
            )

            Spacer(Modifier.height(12.dp))

            TvOutlinedTextField(
                id = "port",
                editingId = editingId,
                setEditingId = { editingId = it },
                value = htspPort,
                onValueChange = { htspPort = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.port_htsp)) }
            )

            Spacer(Modifier.height(12.dp))

            TvOutlinedTextField(
                id = "user",
                editingId = editingId,
                setEditingId = { editingId = it },
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.username)) }
            )

            Spacer(Modifier.height(12.dp))

            TvPasswordField(
                id = "pass",
                editingId = editingId,
                setEditingId = { editingId = it },
                value = pass,
                onValueChange = { pass = it }
            )

            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val pHtsp = htspPort.toIntOrNull() ?: 9982
                scope.launch {
                    settingsStore.saveServer(host, pHtsp, user, auto)
                    passwordStore.setPassword(pass)
                }
            }, modifier = Modifier/*.focusRequester(hostFocus)*/) {
                Text(stringResource(R.string.save))
            }
        }
    }
}