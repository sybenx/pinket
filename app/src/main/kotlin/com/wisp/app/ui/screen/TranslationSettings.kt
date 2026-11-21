package com.wisp.app.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.translate.LibreTranslateClient
import com.wisp.app.translate.OnDeviceTranslator
import com.wisp.app.translate.ServerAddress
import com.wisp.app.translate.TranslationPreferences
import kotlinx.coroutines.delay

/** Under Auto-translate: languages you read, and your own LibreTranslate (optional). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationSettings(application: Application) {
    val prefs = remember { TranslationPreferences(application) }
    var added by remember { mutableStateOf(prefs.addedLanguages()) }
    val phone = remember { TranslationPreferences.phoneLanguages() }
    var adding by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_languages_you_read), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.settings_languages_you_read_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.settings_languages_from_phone, phone.joinToString(", ") { OnDeviceTranslator.name(it) }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (code in added.sortedBy { OnDeviceTranslator.name(it) }) {
            val name = OnDeviceTranslator.name(code)
            InputChip(
                selected = false,
                onClick = {
                    added = added - code
                    prefs.setAddedLanguages(added)
                },
                label = { Text(name) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_remove_language, name),
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
        Box {
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.settings_add_language)) }
            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                val choices = remember(added) { OnDeviceTranslator.all().filter { it !in added && it !in phone } }
                for (code in choices) {
                    DropdownMenuItem(
                        text = { Text(OnDeviceTranslator.name(code)) },
                        onClick = {
                            adding = false
                            added = added + code
                            prefs.setAddedLanguages(added)
                        }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_translate_server), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.settings_translate_server_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    var url by remember { mutableStateOf(prefs.serverUrl()) }
    var key by remember { mutableStateOf(prefs.serverKey()) }
    val status by LibreTranslateClient.status.collectAsState()

    // Saved and checked shortly after you stop typing.
    LaunchedEffect(url, key) {
        delay(800)
        val changed = url.trim() != prefs.serverUrl() || key.trim() != prefs.serverKey()
        when (val parsed = ServerAddress.parse(url)) {
            ServerAddress.Result.PlainHttp -> {
                prefs.setServerUrl("")
                LibreTranslateClient.refuseHttp()
            }
            ServerAddress.Result.Empty -> {
                prefs.setServerUrl("")
                if (changed) LibreTranslateClient.reset()
            }
            is ServerAddress.Result.Ok -> {
                prefs.setServerUrl(parsed.url)
                prefs.setServerKey(key)
                if (changed || status == null) {
                    LibreTranslateClient.reset()
                    LibreTranslateClient.check(parsed.url)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_translate_server_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.settings_translate_server_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        val line = when (val s = status) {
            null -> null
            LibreTranslateClient.Status.Working -> stringResource(R.string.translate_server_working)
            is LibreTranslateClient.Status.NotWorking -> stringResource(R.string.translate_server_not_working, s.why)
            is LibreTranslateClient.Status.Resting -> stringResource(R.string.translate_server_resting, s.why)
            LibreTranslateClient.Status.HttpsOnly -> stringResource(R.string.translate_server_https_only)
        }
        if (line != null && url.isNotBlank()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == LibreTranslateClient.Status.Working) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
