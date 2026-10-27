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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ichthys.apps.R
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.sync.OpState
import org.ichthys.apps.ui.theme.Shoal

/**
 * The catalog's installable apps (kind "app"; the image's system apps are not offered) with
 * checkboxes, pre-checked from apps.install (until it was written: the catalog's `default` entries
 * and the catalog apps that are installed already). The one accented action writes apps.install =
 * the checked ids and apps.confirmed = true through ichthys_config, then installs what is missing
 * and removes what was unchecked, with progress per app. Until that write (apps.install unset, or
 * not confirmed) nothing is installed.
 */
@Composable
fun RecommendedScreen(vm: AppsViewModel, padding: PaddingValues) {
    val config by vm.config.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val catalog = vm.catalog
    val checked = selection ?: vm.preChecked(config, installed)
    val managed = config.managed(catalog).toSet()

    val toInstall = catalog.recommended.count { it.id in checked && it.id !in installed }
    // IchthysOS: a copy from elsewhere with another signer is only forgotten, never removed
    // (AppsManager.removeDropped), so it isn't listed as "will be removed".
    val toRemove =
        catalog.recommended.filter {
            it.id in managed && it.id !in checked && installed[it.id]?.pinned == true
        }
    // IchthysOS: only work on these apps holds the button. A system app updating in the
    // background used to disable it with nothing on this screen saying why.
    val busy = catalog.recommended.any { ops[it.id]?.busy == true }
    // IchthysOS: once a list is confirmed, the button does something only when the ticks differ
    // from apps.install, or a ticked app still isn't installed (its install failed or was
    // declined: the button, which then reads "Install N", tries again). It used to stay enabled
    // as "Apply" with nothing to apply, and disabled as "Install N" after a failed install.
    val recommendedIds = catalog.recommended.map { it.id }
    val changed =
        recommendedIds.filter { it in checked }.toSet() !=
            recommendedIds.filter { it in config.install.orEmpty() }.toSet()
    val label =
        when {
            !config.ready && checked.isEmpty() -> stringResource(R.string.recommended_none)
            toInstall > 0 -> stringResource(R.string.recommended_install_count, toInstall)
            config.ready -> stringResource(R.string.recommended_apply)
            else -> stringResource(R.string.recommended_install)
        }
    val enabled = loaded && !busy && (!config.ready || changed || toInstall > 0)

    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text(
                    stringResource(R.string.recommended_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.recommended_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Shoal.tokens.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!online) OfflineNote()
            }
        }
        if (catalog.recommended.isEmpty()) {
            item {
                ShoalCard {
                    Text(stringResource(R.string.recommended_empty), color = Shoal.tokens.muted)
                }
            }
            return@LazyColumn
        }
        if (!loaded) {
            item { LoadingCard() }
            return@LazyColumn
        }
        item {
            ShoalCard {
                catalog.recommended.forEachIndexed { i, app ->
                    if (i > 0) {
                        HorizontalDivider(
                            color = Shoal.tokens.hairline,
                            thickness = Shoal.tokens.hairlineWidth,
                        )
                    }
                    AppCheckRow(
                        app = app,
                        checked = app.id in checked,
                        installedVersion = installed[app.id]?.versionName,
                        isInstalled = app.id in installed,
                        otherSigner = installed[app.id]?.pinned == false,
                        op = ops[app.id],
                        onToggle = { vm.toggle(app.id, it) },
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                if (toRemove.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.recommended_removes,
                            toRemove.size,
                            toRemove.joinToString { it.name },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Shoal.tokens.danger,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                AccentButton(
                    text = label,
                    onClick = { vm.applySelection() },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AppCheckRow(
    app: CatalogApp,
    checked: Boolean,
    installedVersion: String?,
    isInstalled: Boolean,
    otherSigner: Boolean,
    op: OpState?,
    onToggle: (Boolean) -> Unit,
) {
    val t = Shoal.tokens
    // IchthysOS: one toggleable row (a single TalkBack "checkbox" node with the app's name and
    // description) instead of a clickable row with a second, separately focusable checkbox.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            // The size it has as a touch target of its own, so the row's layout stays put.
            modifier = Modifier.size(48.dp),
            colors =
                CheckboxDefaults.colors(
                    checkedColor = t.text,
                    checkmarkColor = t.ink,
                    uncheckedColor = t.outline,
                ),
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                if (isInstalled) {
                    Tag(
                        installedVersion ?: stringResource(R.string.recommended_installed_tag),
                        emphasized = true,
                    )
                }
            }
            if (app.description.isNotEmpty()) {
                Text(
                    app.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (otherSigner) {
                // IchthysOS: said up front, not only as a failure once it's ticked and installed.
                Text(
                    stringResource(R.string.recommended_other_signer),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) t.danger else t.text,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 6.dp)) {
                if (app.category.isNotEmpty()) Tag(app.category)
                Tag(app.scheme)
            }
            OpStatus(op)
        }
    }
}
