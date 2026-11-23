package com.wisp.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisp.app.R
import com.wisp.app.viewmodel.WipeNotice
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One list that lost entries in another app: what went missing and when, with Put back and Leave it. */
@Composable
fun WipeNoticeCard(
    notice: WipeNotice,
    busy: Boolean,
    onPutBack: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val date = shortDate(notice.noticedAt)
    val n = notice.missing
    val text = when (notice.kind) {
        3 -> pluralStringResource(R.plurals.wipe_missing_follows, n, n, date)
        10000 -> pluralStringResource(R.plurals.wipe_missing_mutes, n, n, date)
        10001 -> pluralStringResource(R.plurals.wipe_missing_pins, n, n, date)
        10003 -> pluralStringResource(R.plurals.wipe_missing_bookmarks, n, n, date)
        else -> pluralStringResource(R.plurals.wipe_missing_set, n, n, notice.title ?: notice.d.orEmpty(), date)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onLeave, enabled = !busy) {
                    Text(stringResource(R.string.wipe_leave_it), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onPutBack, enabled = !busy) {
                    Text(stringResource(R.string.wipe_put_back))
                }
            }
        }
    }
}

/** "Nov 23", with the year when it is not this year. */
private fun shortDate(seconds: Long): String {
    val date = Date(seconds * 1000)
    val then = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    val now = Calendar.getInstance().get(Calendar.YEAR)
    val pattern = if (then == now) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}
