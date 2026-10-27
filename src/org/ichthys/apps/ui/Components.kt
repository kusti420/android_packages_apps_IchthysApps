/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ichthys.apps.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.ichthys.apps.R
import org.ichthys.apps.sync.OpState
import org.ichthys.apps.ui.theme.Shoal

/**
 * One group: the Shoal card on the ink ground. Outlined by a hairline with the hairline surface
 * style, a filled card without a stroke when it is tonal.
 */
@Composable
fun ShoalCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Shoal.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(t.radius),
        color = t.card,
        border = if (t.hairlineSurfaces) BorderStroke(t.hairlineWidth, t.hairline) else null,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) SectionLabel(title)
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Shoal.tokens.mono),
        color = Shoal.tokens.muted,
        // IchthysOS: a heading for TalkBack's heading navigation.
        modifier = modifier.semantics { heading() }.padding(bottom = 8.dp),
    )
}

/** Small mono pill (category, source scheme, version). Tags repeat, so they never take accent. */
@Composable
fun Tag(text: String, emphasized: Boolean = false, danger: Boolean = false) {
    val t = Shoal.tokens
    val fg =
        when {
            danger -> t.danger
            emphasized -> t.text
            else -> t.muted
        }
    val line =
        when {
            danger -> t.danger.copy(alpha = 0.6f)
            emphasized -> t.outline
            else -> t.hairline
        }
    Box(
        Modifier.padding(end = 4.dp)
            .border(t.hairlineWidth, line, RoundedCornerShape(t.radiusSmall))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    supporting: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    // IchthysOS: the whole row toggles (and is one TalkBack node with its label), not only the
    // switch at its end.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            // IchthysOS: a locked row (Follow beta while a beta is installed) looks locked.
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Shoal.tokens.text else Shoal.tokens.muted,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = Shoal.tokens.muted,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The screen's one primary action: the accent fill. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = Shoal.tokens
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(t.radiusSmall),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = t.accent,
                contentColor = t.onAccent,
                disabledContainerColor = t.raised,
                disabledContentColor = t.muted,
            ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary actions: hairline outline, text colour (or danger for destructive ones). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val t = Shoal.tokens
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(t.radiusSmall),
        border =
            BorderStroke(t.hairlineWidth, if (danger) t.danger.copy(alpha = 0.6f) else t.outline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = if (danger) t.danger else t.text,
                disabledContentColor = t.muted,
            ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Asks before a destructive action. Drawn like a [ShoalCard]; the confirming button takes the
 * danger colour, nothing takes the accent.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = Shoal.tokens
    val shape = RoundedCornerShape(t.radius)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { QuietButton(confirm, onClick = onConfirm, danger = true) },
        dismissButton = {
            QuietButton(stringResource(android.R.string.cancel), onClick = onDismiss)
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        modifier =
            if (t.hairlineSurfaces) Modifier.border(t.hairlineWidth, t.hairline, shape)
            else Modifier,
        shape = shape,
        containerColor = t.card,
        titleContentColor = t.text,
        textContentColor = t.muted,
        tonalElevation = 0.dp,
    )
}

/**
 * One line for a running or finished operation, with a bar under it while the app is busy. Only the
 * app being worked on (one at a time) gets the accent bar, which follows the download; apps that
 * are merely queued wait on the plain track.
 */
@Composable
fun OpStatus(op: OpState?) {
    if (op == null) return
    val t = Shoal.tokens
    val text =
        when (op) {
            OpState.Queued -> stringResource(R.string.status_queued)
            OpState.Resolving -> stringResource(R.string.status_resolving)
            is OpState.Downloading ->
                stringResource(R.string.status_downloading) +
                    (op.fraction?.let { " ${(it * 100).toInt()}%" } ?: "")
            OpState.Verifying -> stringResource(R.string.status_verifying)
            OpState.Installing -> stringResource(R.string.status_installing)
            OpState.Uninstalling -> stringResource(R.string.status_uninstalling)
            OpState.Done -> stringResource(R.string.status_done)
            OpState.Updated -> stringResource(R.string.status_updated)
            OpState.Interrupted -> stringResource(R.string.status_interrupted)
            OpState.Removed -> stringResource(R.string.status_removed)
            OpState.UpToDate -> stringResource(R.string.status_up_to_date)
            OpState.WaitingForWifi -> stringResource(R.string.status_waiting_wifi)
            OpState.NeedsUser -> stringResource(R.string.status_user_action)
            is OpState.Failed -> stringResource(R.string.status_failed, op.message)
        }
    val color =
        when (op) {
            is OpState.Failed -> t.danger
            OpState.NeedsUser -> t.text
            else -> t.muted
        }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
            color = color,
        )
        if (op.busy) {
            val fraction = (op as? OpState.Downloading)?.fraction
            val mod = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp)
            when {
                !op.active -> Box(mod.background(t.track))
                fraction != null ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = mod,
                        color = t.accent,
                        trackColor = t.track,
                    )
                else ->
                    LinearProgressIndicator(modifier = mod, color = t.accent, trackColor = t.track)
            }
        }
    }
}

/**
 * "3 min ago" for a millis timestamp; [never] for 0, [justNow] for under a minute (IchthysOS:
 * DateUtils says "0 minutes ago" there).
 */
fun relativeTime(millis: Long, never: String, justNow: String): String {
    if (millis <= 0) return never
    val now = System.currentTimeMillis()
    if (abs(now - millis) < DateUtils.MINUTE_IN_MILLIS) return justNow
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** IchthysOS: in place of the app lists until the installed apps have been read once. */
@Composable
fun LoadingCard() {
    val t = Shoal.tokens
    ShoalCard {
        Text(
            stringResource(R.string.apps_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = t.muted,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp),
            color = t.accent,
            trackColor = t.track,
        )
    }
}

/** IchthysOS: under a screen's title while the phone has no internet connection. */
@Composable
fun OfflineNote() {
    Text(
        stringResource(R.string.offline_note),
        style = MaterialTheme.typography.bodySmall,
        color = Shoal.tokens.text,
        modifier = Modifier.padding(top = 8.dp),
    )
}
