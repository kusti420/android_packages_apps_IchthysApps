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

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ichthys.apps.R
import java.io.IOException
import org.ichthys.apps.catalog.Catalog
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.config.AppsConfig
import org.ichthys.apps.config.AppsState
import org.ichthys.apps.config.ConfigException
import org.ichthys.apps.install.CertVerifier
import org.ichthys.apps.notify.Notifications
import org.ichthys.apps.source.DeviceSpec
import org.ichthys.apps.source.SourceRegistry
import org.ichthys.apps.sync.Adoption
import org.ichthys.apps.sync.AppRecord
import org.ichthys.apps.sync.AppStore
import org.ichthys.apps.sync.AppsManager
import org.ichthys.apps.sync.OpState
import org.ichthys.apps.sync.OtherApp
import org.ichthys.apps.sync.SourceLookup

/** What the phone has installed for one app. */
data class InstalledApp(val versionName: String?, val versionCode: Long)

/**
 * The "Keep updated" dialog for one app installed from elsewhere.
 *
 * @property pins the certificate(s) the installed copy is signed with: what adopting it pins
 * @property looking the F-Droid lookup is still running
 * @property found the source the lookup found; null: the user names one
 * @property otherSigner F-Droid has the app, but signs it with another key
 * @property working the source is being checked and the adoption written
 * @property error why the last attempt failed
 */
data class AdoptDialog(
    val app: OtherApp,
    val pins: Set<String>,
    val looking: Boolean = true,
    val found: String? = null,
    val otherSigner: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/**
 * Screen state: the catalog, apps.* (live from Settings.Secure), what is installed, per-app records
 * and the running operations. Every change goes through [AppsConfig] (ichthys_config) and
 * [AppsManager]; the screens never touch PackageInstaller themselves.
 */
class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val store = AppStore(ctx)
    val catalog: Catalog = Catalog.get()

    private val _config = MutableStateFlow(AppsConfig.read(ctx))
    val config: StateFlow<AppsState> = _config.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, InstalledApp>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledApp>> = _installed.asStateFlow()

    private val _records = MutableStateFlow<Map<String, AppRecord>>(emptyMap())
    val records: StateFlow<Map<String, AppRecord>> = _records.asStateFlow()

    /** apps.adopted, as apps (id = package name). */
    private val _adopted = MutableStateFlow<List<CatalogApp>>(emptyList())
    val adopted: StateFlow<List<CatalogApp>> = _adopted.asStateFlow()

    /** The user apps Ichthys Apps doesn't handle (Installed › Other apps). */
    private val _others = MutableStateFlow<List<OtherApp>>(emptyList())
    val others: StateFlow<List<OtherApp>> = _others.asStateFlow()

    /** The "Keep updated" dialog, while it is open. */
    private val _adopting = MutableStateFlow<AdoptDialog?>(null)
    val adopting: StateFlow<AdoptDialog?> = _adopting.asStateFlow()

    val ops: StateFlow<Map<String, OpState>> = AppsManager.ops

    /** The Recommended checkboxes while edited; null = follow apps.install. */
    private val _selection = MutableStateFlow<Set<String>?>(null)
    val selection: StateFlow<Set<String>?> = _selection.asStateFlow()

    /** A config write that failed, shown once. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _config.value = AppsConfig.read(ctx)
                refresh()
            }
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }

    init {
        for (key in AppsConfig.KEYS) {
            ctx.contentResolver.registerContentObserver(AppsConfig.uri(key), false, observer)
        }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
        ctx.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Re-read installed versions when an operation settles (not on every progress tick).
        viewModelScope.launch {
            AppsManager.ops
                .map { ops -> ops.filterValues { !it.busy } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        refresh()
    }

    override fun onCleared() {
        ctx.contentResolver.unregisterContentObserver(observer)
        ctx.unregisterReceiver(packageReceiver)
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = AppsConfig.read(ctx)
            val adoptedApps = Adoption.apps(ctx, state)
            val inst = HashMap<String, InstalledApp>()
            val recs = HashMap<String, AppRecord>()
            for (app in catalog.apps + adoptedApps) {
                AppsManager.installedInfo(ctx, app.packageName)?.let {
                    inst[app.id] = InstalledApp(it.versionName, it.longVersionCode)
                }
                recs[app.id] = store.record(app.id)
            }
            _installed.value = inst
            _records.value = recs
            _adopted.value = adoptedApps
            _others.value = Adoption.others(ctx, catalog, state)
        }
    }

    /**
     * The ids checked on the Recommended screen before the user touches it: apps.install, or, while
     * that was never written, the catalog's `default` ids and the catalog apps that are on the
     * phone already. Earlier builds shipped some of them (Termux, KernelSU Next, …); what is
     * installed stays looked after unless the user unchecks it.
     */
    fun preChecked(config: AppsState, installed: Map<String, InstalledApp>): Set<String> =
        config.install?.toSet()
            ?: (catalog.defaultIds + catalog.recommended.map { it.id }.filter { it in installed })
                .toSet()

    /** The checked ids: the edit in progress, else [preChecked]. */
    fun checked(): Set<String> = _selection.value ?: preChecked(_config.value, _installed.value)

    fun toggle(id: String, on: Boolean) {
        val cur = checked()
        _selection.value = if (on) cur + id else cur - id
    }

    /**
     * Recommended's Install: apps.install = the checked ids, written out even when they are just
     * the defaults, and apps.confirmed = true; then sync.
     */
    fun applySelection() {
        val ids = catalog.recommended.map { it.id }.filter { it in checked() }
        writeConfig {
            AppsManager.applySelection(ctx, ids)
            _selection.value = null
            Notifications.checkFirstRun(ctx)
        }
    }

    fun remove(id: String) = writeConfig { AppsManager.remove(ctx, id) }

    fun updateNow(id: String) {
        AppsManager.userSync(ctx, checkUpdates = true, only = setOf(id), force = true)
    }

    fun checkAll() {
        AppsManager.userSync(ctx, checkUpdates = true)
    }

    /**
     * Opens the "Keep updated" dialog for [app] and looks it up on F-Droid. When F-Droid has the
     * same build, that is its source; otherwise the dialog asks for one.
     */
    fun startAdopt(app: OtherApp) {
        val pins = CertVerifier.installedSigners(ctx, app.packageName).orEmpty()
        _adopting.value = AdoptDialog(app, pins)
        viewModelScope.launch(Dispatchers.IO) {
            val result = Adoption.lookUp(ctx, app.packageName)
            _adopting.update { d ->
                // Closed, or opened for another app, in the meantime.
                if (d == null || d.app.packageName != app.packageName) return@update d
                when (result) {
                    is SourceLookup.Found -> d.copy(looking = false, found = result.source)
                    SourceLookup.OtherSigner -> d.copy(looking = false, otherSigner = true)
                    SourceLookup.NotFound -> d.copy(looking = false)
                    is SourceLookup.Failed -> d.copy(looking = false, error = result.message)
                }
            }
        }
    }

    fun cancelAdopt() {
        _adopting.value = null
    }

    /**
     * The dialog's confirmation: adopts the app from the source F-Droid gave, else from [typed]
     * (see [Adoption.parseSource]). A source the user named is asked for its newest release
     * first, so a wrong address shows here and not as a failed update later.
     */
    fun confirmAdopt(typed: String) {
        val dialog = _adopting.value ?: return
        val pkg = dialog.app.packageName
        val source = dialog.found ?: Adoption.parseSource(typed)
        if (source == null) {
            _adopting.value = dialog.copy(error = ctx.getString(R.string.adopt_bad_source))
            return
        }
        _adopting.value = dialog.copy(working = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val error =
                try {
                    if (dialog.found == null) {
                        SourceRegistry.init(ctx)
                        SourceRegistry.forSource(source)
                            .resolve(source.substringAfter(':'), DeviceSpec.of(ctx))
                    }
                    AppsManager.adopt(ctx, pkg, source)
                    null
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                } catch (e: ConfigException) {
                    ctx.getString(R.string.config_error, e.message ?: "")
                }
            _adopting.update { d ->
                when {
                    d == null || d.app.packageName != pkg -> d
                    error == null -> null
                    else -> d.copy(working = false, error = error)
                }
            }
            if (error == null) {
                _config.value = AppsConfig.read(ctx)
                refresh()
            }
        }
    }

    /** Stops updating an adopted app; it stays installed. */
    fun unadopt(packageName: String) = writeConfig {
        AppsManager.unadopt(ctx, packageName)
        refresh()
    }

    fun setAutoUpdate(on: Boolean) = writeConfig { AppsConfig.setAutoUpdate(on) }

    fun setWifiOnly(on: Boolean) = writeConfig { AppsConfig.setWifiOnly(on) }

    fun dismissError() {
        _error.value = null
    }

    private fun writeConfig(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                _config.value = AppsConfig.read(ctx)
            } catch (e: ConfigException) {
                _error.value = ctx.getString(R.string.config_error, e.message ?: "")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AppsViewModel(this[APPLICATION_KEY]!!) }
        }
    }
}
