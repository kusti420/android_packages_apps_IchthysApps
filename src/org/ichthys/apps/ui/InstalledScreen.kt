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

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ichthys.apps.R
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.catalog.Kind
import org.ichthys.apps.sync.AppRecord
import org.ichthys.apps.sync.AppsManager
import org.ichthys.apps.sync.OpState
import org.ichthys.apps.sync.OtherApp
import org.ichthys.apps.ui.theme.Shoal

/** How much of a certificate's SHA-256 is shown: enough to compare by eye. */
private const val PIN_DIGITS = 16

/**
 * Everything Ichthys Apps keeps updated, and what it could:
 * - the apps it manages: the catalog apps of apps.install (once confirmed) and the adopted apps
 *   (apps.adopted), each with its installed version (and the newest one the source reported, and
 *   a newer beta), source, last check and "Update now"; "Follow beta" once there is a beta.
 *   "Remove" (after a confirmation) drops a catalog app from apps.install, and the sync
 *   uninstalls it; "Stop updating" drops an adopted app from
 *   apps.adopted and leaves it installed;
 * - the image's system apps, which are only updated;
 * - the other user apps, each with "Keep updated", which adopts it.
 *
 * On top, apps.autoUpdate and apps.wifiOnly.
 */
@Composable
fun InstalledScreen(vm: AppsViewModel, padding: PaddingValues) {
    val config by vm.config.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val adopted by vm.adopted.collectAsStateWithLifecycle()
    val others by vm.others.collectAsStateWithLifecycle()
    val adopting by vm.adopting.collectAsStateWithLifecycle()
    val apps = config.managed(vm.catalog).mapNotNull { vm.catalog[it] } + adopted
    val system = vm.catalog.bundled.filter { it.id in installed }
    val busy = ops.values.any { it.busy }
    // The app whose "Remove" is waiting for its confirmation.
    var removing by remember { mutableStateOf<CatalogApp?>(null) }

    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.installed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (apps.isNotEmpty() || system.isNotEmpty()) {
                    QuietButton(
                        stringResource(R.string.installed_check_all),
                        onClick = { vm.checkAll() },
                        enabled = !busy,
                    )
                }
            }
        }
        item {
            ShoalCard(title = stringResource(R.string.installed_settings)) {
                ToggleRow(
                    label = stringResource(R.string.installed_auto_update),
                    supporting = stringResource(R.string.installed_auto_update_summary),
                    checked = config.autoUpdate,
                    onChange = { vm.setAutoUpdate(it) },
                )
                ToggleRow(
                    label = stringResource(R.string.installed_wifi_only),
                    supporting = stringResource(R.string.installed_wifi_only_summary),
                    checked = config.wifiOnly,
                    onChange = { vm.setWifiOnly(it) },
                )
            }
        }
        if (apps.isEmpty()) {
            item {
                ShoalCard {
                    Text(stringResource(R.string.installed_empty), color = Shoal.tokens.muted)
                }
            }
        }
        items(apps, key = { it.id }) { app ->
            InstalledCard(
                app = app,
                installed = installed[app.id],
                record = records[app.id],
                op = ops[app.id],
                onUpdate = { vm.updateNow(app.id) },
                onFollowBeta = { vm.setFollowBeta(app.id, it) },
                onRemove = {
                    // Nothing is lost by no longer updating an app, so that needs no confirmation.
                    if (app.kind == Kind.ADOPTED) {
                        vm.unadopt(app.packageName)
                    } else {
                        removing = app
                    }
                },
            )
        }
        if (system.isNotEmpty()) {
            item {
                ShoalCard(title = stringResource(R.string.installed_system_title)) {
                    Summary(stringResource(R.string.installed_system_summary))
                    system.forEach { app ->
                        Hairline()
                        SystemAppRow(app, installed[app.id], records[app.id], ops[app.id])
                    }
                }
            }
        }
        if (others.isNotEmpty()) {
            item {
                ShoalCard(title = stringResource(R.string.installed_other_title)) {
                    Summary(stringResource(R.string.installed_other_summary))
                    others.forEach { other ->
                        Hairline()
                        OtherAppRow(other, onAdopt = { vm.startAdopt(other) })
                    }
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
    removing?.let { app ->
        ConfirmDialog(
            title = stringResource(R.string.installed_remove_title, app.name),
            text = stringResource(R.string.installed_remove_text),
            confirm = stringResource(R.string.installed_remove),
            onConfirm = {
                removing = null
                vm.remove(app.id)
            },
            onDismiss = { removing = null },
        )
    }
    adopting?.let { dialog ->
        AdoptDialogView(
            dialog = dialog,
            onConfirm = { vm.confirmAdopt(it) },
            onDismiss = { vm.cancelAdopt() },
        )
    }
}

@Composable
private fun InstalledCard(
    app: CatalogApp,
    installed: InstalledApp?,
    record: AppRecord?,
    op: OpState?,
    onUpdate: () -> Unit,
    onFollowBeta: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val busy = op?.busy == true
    val adopted = app.kind == Kind.ADOPTED
    ShoalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            when {
                adopted -> Tag(stringResource(R.string.installed_adopted_tag))
                app.category.isNotEmpty() -> Tag(app.category)
            }
        }
        val never = stringResource(R.string.installed_never)
        Column(Modifier.padding(top = 8.dp)) {
            Field(stringResource(R.string.installed_version), versionText(installed, record))
            Field(stringResource(R.string.installed_source), app.source)
            if (adopted) {
                // Not a key the ROM vouches for: say so wherever it is shown.
                Field(
                    stringResource(R.string.installed_pin),
                    stringResource(R.string.installed_pin_tofu, shortPins(app.certs)),
                )
            }
            Field(
                stringResource(R.string.installed_last_check),
                relativeTime(record?.lastCheck ?: 0L, never),
            )
        }
        if (record != null) {
            // Already on a beta: it follows betas until stable overtakes it, whatever the switch.
            val onBeta = AppsManager.onBeta(installed?.versionCode, record.latestCode)
            if (record.latestBeta != null || record.followBeta || onBeta) {
                ToggleRow(
                    label = stringResource(R.string.installed_follow_beta),
                    supporting =
                        stringResource(
                            if (onBeta && !record.followBeta) R.string.installed_on_beta_summary
                            else R.string.installed_follow_beta_summary
                        ),
                    checked = record.followBeta || onBeta,
                    enabled = !busy && !(onBeta && !record.followBeta),
                    onChange = onFollowBeta,
                )
            }
        }
        when {
            op != null -> OpStatus(op)
            record?.lastError != null -> OpStatus(OpState.Failed(record.lastError))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            QuietButton(
                stringResource(R.string.installed_update_now),
                onClick = onUpdate,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            QuietButton(
                stringResource(
                    if (adopted) R.string.installed_stop_updating else R.string.installed_remove
                ),
                onClick = onRemove,
                enabled = !busy,
                danger = !adopted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A system app: name, version and source. It is only ever updated, so it has no actions. */
@Composable
private fun SystemAppRow(
    app: CatalogApp,
    installed: InstalledApp?,
    record: AppRecord?,
    op: OpState?,
) {
    val t = Shoal.tokens
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                versionText(installed, record),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                color = t.muted,
                maxLines = 1,
            )
        }
        Text(
            app.source,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
            color = t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            op != null -> OpStatus(op)
            record?.lastError != null -> OpStatus(OpState.Failed(record.lastError))
        }
    }
}

/** A user app Ichthys Apps doesn't handle, with the action that adopts it. */
@Composable
private fun OtherAppRow(app: OtherApp, onAdopt: () -> Unit) {
    val t = Shoal.tokens
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val from =
                app.installer?.let { " · " + stringResource(R.string.installed_other_from, it) }
            Text(
                app.packageName + from.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = t.mono),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QuietButton(stringResource(R.string.installed_keep_updated), onClick = onAdopt)
    }
}

/**
 * "Keep updated": the source the app will be updated from (F-Droid's when it has the same build,
 * else one the user names) and, always, what is being trusted: the certificate of the copy that is
 * installed now, on first use.
 */
@Composable
private fun AdoptDialogView(
    dialog: AdoptDialog,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = Shoal.tokens
    val shape = RoundedCornerShape(t.radius)
    var typed by remember(dialog.app.packageName) { mutableStateOf("") }
    val ready = !dialog.looking && !dialog.working
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            QuietButton(
                stringResource(R.string.adopt_confirm),
                onClick = { onConfirm(typed) },
                enabled = ready && (dialog.found != null || typed.isNotBlank()),
            )
        },
        dismissButton = {
            QuietButton(stringResource(android.R.string.cancel), onClick = onDismiss)
        },
        title = {
            Text(
                stringResource(R.string.adopt_title, dialog.app.label),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                when {
                    dialog.looking -> {
                        Text(
                            stringResource(R.string.adopt_looking),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp),
                            color = t.accent,
                            trackColor = t.track,
                        )
                    }
                    dialog.found != null ->
                        Text(
                            stringResource(R.string.adopt_found, dialog.found),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                            color = t.text,
                        )
                    else -> {
                        Text(
                            stringResource(
                                if (dialog.otherSigner) R.string.adopt_other_signer
                                else R.string.adopt_not_found
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            placeholder = { Text(stringResource(R.string.adopt_source_hint)) },
                            singleLine = true,
                            enabled = !dialog.working,
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(fontFamily = t.mono),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        QuietButton(
                            stringResource(R.string.adopt_use_apkpure),
                            onClick = { typed = "apkpure:" + dialog.app.packageName },
                            enabled = !dialog.working,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (dialog.working) {
                    Text(
                        stringResource(R.string.adopt_checking),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                dialog.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = t.mono),
                        color = t.danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.adopt_tofu, shortPins(dialog.pins)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
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
 * "1.2.3", "1.2.3 → 1.3.0" when the source reported a newer one, and " · 2.0.0 beta available"
 * after it when it reported a newer pre-release. Compared by versionCode where the source gives
 * one, else by name: an installed beta is not "older" than the stable release below it.
 */
@Composable
private fun versionText(installed: InstalledApp?, record: AppRecord?): String {
    if (installed == null) return stringResource(R.string.installed_not_installed)
    val name = installed.versionName ?: installed.versionCode.toString()
    if (record == null) return name
    val update =
        newer(installed, name, record.latestVersion, record.latestCode) &&
            // A name-only source: the installed copy is the beta, not something older.
            !sameName(record.latestBeta, name)
    val stable = if (update) "$name → ${record.latestVersion}" else name
    val beta = record.latestBeta
    if (beta == null || !newer(installed, name, beta, record.latestBetaCode)) return stable
    val labelled = Regex("alpha|beta|rc|pre", RegexOption.IGNORE_CASE).containsMatchIn(beta)
    return stable +
        " · " +
        stringResource(
            if (labelled) R.string.installed_prerelease_available
            else R.string.installed_beta_available,
            beta,
        )
}

/** Whether the version [latest] (versionCode [code], if known) is newer than the installed one. */
private fun newer(installed: InstalledApp, name: String, latest: String?, code: Long?): Boolean =
    when {
        code != null -> code > installed.versionCode
        latest != null -> !sameName(latest, name)
        else -> false
    }

private fun sameName(a: String?, b: String): Boolean =
    a != null && a.removePrefix("v").removePrefix("V") == b.removePrefix("v").removePrefix("V")

private fun shortPins(pins: Set<String>): String =
    pins.sorted().joinToString { it.take(PIN_DIGITS) + "…" }

@Composable
private fun Summary(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Shoal.tokens.muted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Hairline() {
    HorizontalDivider(color = Shoal.tokens.hairline, thickness = Shoal.tokens.hairlineWidth)
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Shoal.tokens.mono),
            color = Shoal.tokens.muted,
            modifier = Modifier.width(96.dp).padding(top = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Shoal.tokens.mono),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
