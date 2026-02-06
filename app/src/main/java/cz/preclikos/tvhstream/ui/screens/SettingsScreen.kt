package cz.preclikos.tvhstream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    passwordStore: SecurePasswordStore,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf("") }
    var htspPort by rememberSaveable { mutableStateOf("9982") }
    var httpPort by rememberSaveable { mutableStateOf("9981") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var auto by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        settingsStore.serverSettings.collect { s ->
            host = s.host
            htspPort = s.htspPort.toString()
            httpPort = s.httpPort.toString()
            user = s.username
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
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 10.dp) // aby to sedělo výškově s ikonou
        )

        Column {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.host)) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = htspPort,
                onValueChange = { htspPort = it },
                label = { Text(stringResource(R.string.port_htsp)) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = httpPort,
                onValueChange = { httpPort = it },
                label = { Text(stringResource(R.string.port_http)) }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.username)) }
            )
            Spacer(Modifier.height(12.dp))

            PasswordField(
                value = pass,
                onValueChange = { pass = it }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val pHtsp = htspPort.toIntOrNull() ?: 9982
                val pHttp = httpPort.toIntOrNull() ?: 9981
                scope.launch {
                    settingsStore.saveServer(host, pHtsp, pHttp, user, auto)
                    passwordStore.setPassword(pass)
                    onDone()
                }
            }) {
                Text(stringResource(R.string.save))
            }

            OutlinedButton(onClick = onDone) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var passwordVisible by remember { mutableStateOf(false) }

    val icon = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility
    val desc =
        stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(R.string.password)) },

        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },

        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(imageVector = icon, contentDescription = desc)
            }
        },

        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        singleLine = true
    )
}