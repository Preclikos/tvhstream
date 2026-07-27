package cz.preclikos.tvhstream.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import cz.preclikos.tvhstream.R
import cz.preclikos.tvhstream.settings.AppLanguage

@Composable
fun SettingsLanguage() {
    val selected = AppLanguage.fromLanguageTags(
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    )
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_follow_system),
        AppLanguage.GERMAN to stringResource(R.string.language_german),
        AppLanguage.ENGLISH to stringResource(R.string.language_english),
        AppLanguage.CZECH to stringResource(R.string.language_czech),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 10.dp),
        )

        Column(Modifier.selectableGroup()) {
            options.forEach { (language, label) ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = language == selected,
                            onClick = {
                                if (language != selected) {
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags(language.languageTag)
                                    )
                                }
                            },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = language == selected,
                        onClick = null,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
