package cz.preclikos.tvhstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vm: AppConnectionViewModel, // kvůli statusu (volitelné)
    settingsStore: SettingsStore,
    passwordStore: SecurePasswordStore,
    onDone: () -> Unit
) {
    val status by vm.status.collectAsState()

    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("9982") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var auto by rememberSaveable { mutableStateOf(true) }

    // načti současné hodnoty jednou
    LaunchedEffect(Unit) {
        settingsStore.serverSettings.collect { s ->
            host = s.host
            port = s.port.toString()
            user = s.username
            auto = s.autoConnect
            pass = passwordStore.getPassword()
            return@collect
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") })
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = auto, onCheckedChange = { auto = it })
            Text("Auto-connect")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val p = port.toIntOrNull() ?: 9982
                MainScope().launch {
                    settingsStore.saveServer(host, p, user, auto)
                    passwordStore.setPassword(pass)
                    onDone()
                }
            }) { Text("Save") }

            OutlinedButton(onClick = onDone) { Text("Back") }
        }
    }
}