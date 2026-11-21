package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.repo.TranslationState
import com.wisp.app.repo.TranslationStatus
import com.wisp.app.translate.OnDeviceTranslator

/**
 * The small line at the top of a post's body: "Translated from Spanish · Show original" once translated (the
 * body itself shows the translation), a one-tap "Translate from Spanish", or "Translate from…" with the
 * likeliest languages and Other. Nothing for a post that needs none.
 */
@Composable
fun TranslationLine(
    state: TranslationState,
    showTranslation: Boolean,
    onToggle: () -> Unit,
    onTranslateFrom: (code: String, remember: Boolean) -> Unit,
    onPickerShown: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val lineModifier = Modifier.padding(bottom = 4.dp).heightIn(min = 20.dp)
    when (state.status) {
        TranslationStatus.DONE -> {
            val toggle = if (showTranslation) stringResource(R.string.translate_line_show_original)
            else stringResource(R.string.translate_line_show_translation)
            Text(
                text = stringResource(R.string.translate_translated_from, state.sourceLanguage) + " · " + toggle,
                style = style,
                color = muted,
                modifier = lineModifier.clickable(onClick = onToggle)
            )
        }
        TranslationStatus.HINT -> Text(
            text = stringResource(R.string.translate_from_language, state.sourceLanguage),
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = lineModifier.clickable { onTranslateFrom(state.sourceCode, false) }
        )
        TranslationStatus.PICK -> Box {
            var open by remember { mutableStateOf(false) }
            var other by remember { mutableStateOf(false) }
            LaunchedEffect(state.openPicker) {
                if (state.openPicker) {
                    open = true
                    other = state.likeliest.isEmpty()
                    onPickerShown()
                }
            }
            Text(
                text = stringResource(R.string.translate_from_pick),
                style = style,
                color = MaterialTheme.colorScheme.primary,
                modifier = lineModifier.clickable { open = true; other = state.likeliest.isEmpty() }
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false; other = false }) {
                val choices = if (other) remember { OnDeviceTranslator.all() } else state.likeliest
                for (code in choices) {
                    DropdownMenuItem(
                        text = { Text(OnDeviceTranslator.name(code)) },
                        onClick = { open = false; other = false; onTranslateFrom(code, true) }
                    )
                }
                if (!other) DropdownMenuItem(
                    text = { Text(stringResource(R.string.translate_other_language), color = muted) },
                    onClick = { other = true }
                )
            }
        }
        TranslationStatus.IDENTIFYING_LANGUAGE,
        TranslationStatus.DOWNLOADING_MODEL,
        TranslationStatus.TRANSLATING -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = lineModifier
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = when (state.status) {
                    TranslationStatus.IDENTIFYING_LANGUAGE -> stringResource(R.string.translate_detecting_language)
                    TranslationStatus.DOWNLOADING_MODEL -> stringResource(R.string.translate_downloading_model)
                    else -> stringResource(R.string.translate_translating)
                },
                style = style,
                color = muted
            )
        }
        TranslationStatus.ERROR -> Text(
            text = state.errorMessage,
            style = style,
            color = MaterialTheme.colorScheme.error,
            modifier = lineModifier
        )
        else -> {}
    }
}
