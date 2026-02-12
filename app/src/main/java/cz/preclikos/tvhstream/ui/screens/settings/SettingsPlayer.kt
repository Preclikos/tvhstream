package cz.preclikos.tvhstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.viewmodels.ProfilesUiState
import cz.preclikos.tvhstream.viewmodels.SettingsPlayerViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlayer(
    vm: SettingsPlayerViewModel = koinViewModel()
) {
    val ui by vm.ui.collectAsState()

    var expanded by remember { mutableStateOf(false) }

    val items = (ui.profiles as? ProfilesUiState.Ready)?.items.orEmpty()
    val canOpen = ui.connected && items.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_player),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 10.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (canOpen) expanded = it }
        ) {
            val textValue = when (val st = ui.profiles) {
                ProfilesUiState.Idle ->
                    if (ui.connected) stringResource(R.string.loading_wait)
                    else stringResource(R.string.not_connected)

                ProfilesUiState.Loading -> stringResource(R.string.loading)
                is ProfilesUiState.Error -> stringResource(R.string.loading_error)
                is ProfilesUiState.Ready -> {
                    val selected = st.items.firstOrNull { it.id == ui.selectedProfileUuid }
                    selected?.name ?: stringResource(R.string.select_profile)
                }
            }

            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                value = textValue,
                onValueChange = {},
                readOnly = true,
                enabled = canOpen,
                label = { Text(stringResource(R.string.profile)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                items.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            vm.onProfileSelected(p)
                            expanded = false
                        }
                    )
                }
            }
        }

        val err = (ui.profiles as? ProfilesUiState.Error)?.message
        if (err != null) {
            Text(
                text = stringResource(R.string.error_prefix) + " $err",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}