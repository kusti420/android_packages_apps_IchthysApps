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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ichthys.apps.R
import org.ichthys.apps.config.AppsConfig
import org.ichthys.apps.sync.AppStore
import org.ichthys.apps.sync.AppsManager
import org.ichthys.apps.ui.theme.AppsTheme
import org.ichthys.apps.ui.theme.Shoal
import org.ichthys.apps.update.UpdateJob

enum class Tab(val label: Int) {
    RECOMMENDED(R.string.tab_recommended),
    INSTALLED(R.string.tab_installed),
}

/**
 * Ichthys Apps: Recommended (pick and install) and Installed (versions, updates, removal). Opens on
 * Recommended until the list is confirmed, or when started with [ACTION_RECOMMENDED] (the first-run
 * notification); on Installed otherwise or with [ACTION_INSTALLED] (problem notifications).
 */
class MainActivity : ComponentActivity() {
    private val tab = mutableStateOf(Tab.RECOMMENDED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tab.value = if (AppsConfig.read(this).ready) Tab.INSTALLED else Tab.RECOMMENDED
        handleIntent(intent)
        UpdateJob.scheduleAll(this)
        // Normally pre-granted (default-permissions-org.ichthys.apps.xml). If it was revoked, ask
        // once; after a denial the user is not asked again on every launch.
        val store = AppStore(this)
        if (
            !store.notificationsAsked &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            store.notificationsAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AppsTheme { AppsApp(tab.value) { tab.value = it } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppsManager.uiVisible = true
    }

    override fun onStop() {
        AppsManager.uiVisible = false
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_RECOMMENDED -> tab.value = Tab.RECOMMENDED
            ACTION_INSTALLED -> tab.value = Tab.INSTALLED
        }
    }

    companion object {
        const val ACTION_RECOMMENDED = "org.ichthys.apps.action.RECOMMENDED"
        const val ACTION_INSTALLED = "org.ichthys.apps.action.INSTALLED"
    }
}

@Composable
private fun AppsApp(tab: Tab, onTab: (Tab) -> Unit) {
    val vm: AppsViewModel = viewModel(factory = AppsViewModel.Factory)
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val e = error ?: return@LaunchedEffect
        snackbar.showSnackbar(e)
        vm.dismissError()
    }
    Scaffold(
        containerColor = Shoal.tokens.ink,
        topBar = { TabBar(tab, onTab) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            Tab.RECOMMENDED -> RecommendedScreen(vm, padding)
            Tab.INSTALLED -> InstalledScreen(vm, padding)
        }
    }
}

/** App name, then the tabs; the selected one is the bar's single accented element. */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    val t = Shoal.tokens
    Column(Modifier.fillMaxWidth().background(t.ink).statusBarsPadding()) {
        Text(
            stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = t.mono),
            color = t.muted,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (item in Tab.entries) {
                val selected = item == tab
                Column(
                    Modifier.width(IntrinsicSize.Max)
                        .clickable { onTab(item) }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        stringResource(item.label),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) t.text else t.muted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) t.accent else t.ink)
                    )
                }
            }
        }
        HorizontalDivider(color = t.hairline, thickness = t.hairlineWidth)
    }
}
