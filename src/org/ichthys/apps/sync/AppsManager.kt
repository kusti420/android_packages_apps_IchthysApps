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

package org.ichthys.apps.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ichthys.apps.catalog.Catalog
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.catalog.Kind
import org.ichthys.apps.config.Adopted
import org.ichthys.apps.config.AppsConfig
import org.ichthys.apps.config.ConfigException
import org.ichthys.apps.install.CertMismatchException
import org.ichthys.apps.install.CertVerifier
import org.ichthys.apps.install.Installer
import org.ichthys.apps.install.Outcome
import org.ichthys.apps.notify.Notifications
import org.ichthys.apps.notify.Notifications.FailureKind
import org.ichthys.apps.source.DeviceSpec
import org.ichthys.apps.source.Http
import org.ichthys.apps.source.Release
import org.ichthys.apps.source.SourceRegistry
import org.ichthys.apps.update.UpdateJob

/** What is happening to one app right now (shown per row; not persisted). */
sealed interface OpState {
    data object Queued : OpState

    data object Resolving : OpState

    data class Downloading(val done: Long, val total: Long) : OpState {
        val fraction: Float?
            get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data object Verifying : OpState

    data object Installing : OpState

    data object Uninstalling : OpState

    data object Done : OpState

    /** IchthysOS: a newer version was installed over the one on the phone. */
    data object Updated : OpState

    /**
     * IchthysOS: stopped from outside (the job's time or network went) before it finished; what was
     * downloaded stays for the next attempt. Was shown as "Failed: cancelled".
     */
    data object Interrupted : OpState

    data object Removed : OpState

    data object UpToDate : OpState

    data object WaitingForWifi : OpState

    data object NeedsUser : OpState

    data class Failed(val message: String) : OpState

    /** Being worked on right now. Apps are handled one at a time, so at most one is. */
    val active: Boolean
        get() =
            this is Resolving ||
                this is Downloading ||
                this is Verifying ||
                this is Installing ||
                this is Uninstalling

    val busy: Boolean
        get() = this is Queued || active
}

/** Why a sync runs: user actions ignore apps.wifiOnly and always check for updates. */
enum class Trigger {
    USER,
    BOOT,
    DAILY,
    CONFIG,
}

/**
 * The one installer and updater of the ROM. One sync at a time, whoever asks (UI, [UpdateJob]):
 * - [Kind.APP], the catalog apps of apps.install: uninstalls ids that left the list, installs ids
 *   that are missing and updates installed ones. Nothing of that happens before apps.confirmed
 *   with an explicit apps.install ([org.ichthys.apps.config.AppsState.ready]).
 * - [Kind.BUNDLED], the image's system apps, and [Kind.ADOPTED], the apps installed from
 *   elsewhere that the user told it to keep updated (apps.adopted): only updated, never installed
 *   or removed.
 *
 * Updates run with apps.autoUpdate, or when the user asks.
 *
 * Pre-releases ([Release.beta]) are recorded and shown, but only installed for an app that follows
 * them: the user turned that on for it, or its installed version is already newer than the newest
 * stable release (it was put on a beta), until a stable release overtakes it.
 *
 * An install: resolve the newest release from the source → skip if it is provably not newer →
 * download to the cache → unpack (XAPK, zip) → verify every APK against the app's cert pins → one
 * PackageInstaller session → record the release fingerprint. An interrupted download stays in the
 * cache and is continued by the next attempt at the same release.
 */
object AppsManager {
    private const val TAG = "IchthysApps"

    /**
     * A background pass on a metered network leaves downloads above this for an unmetered one,
     * whatever apps.wifiOnly says. What the user starts in the app is downloaded at once.
     */
    const val LARGE_DOWNLOAD_BYTES = 100L shl 20

    /**
     * A background update check that fails is only worth a notification once it has failed the
     * same way this many passes in a row: the app is installed and works, and sources have bad
     * days (an outage, GitHub's request limit on a shared address).
     */
    private const val FAILED_CHECKS_BEFORE_NOTICE = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One sync at a time. Held across downloads and installs, so for minutes. */
    private val mutex = Mutex()

    /**
     * Guards a read-modify-write of apps.install or apps.adopted ([remove], [adopt], [unadopt],
     * [onPackageRemoved]). Only ever held for that one binder call, so a broadcast receiver can
     * take it without waiting for a sync.
     */
    private val configLock = Any()

    private val _ops = MutableStateFlow<Map<String, OpState>>(emptyMap())

    /** App id (catalog id, or an adopted app's package name) → current operation, for the UI. */
    val ops: StateFlow<Map<String, OpState>> = _ops.asStateFlow()

    /** Set by the activity while visible: confirmations are then started directly. */
    @Volatile var uiVisible = false

    private fun set(id: String, state: OpState) = _ops.update { it + (id to state) }

    /** [sync] in the process scope: short work (a removal), or when no job can be scheduled. */
    fun launchSync(
        context: Context,
        checkUpdates: Boolean,
        only: Set<String>? = null,
        force: Boolean = false,
    ): Job = scope.launch { sync(context, Trigger.USER, checkUpdates, only, force) }

    /**
     * A [sync] the user asked for (Install, Update now, Check for updates). It runs as
     * [UpdateJob]'s user-initiated data-transfer job, so a long download goes on, under its
     * notification, after the user has left the app. Returns false when it runs in this process
     * instead: without a working network (the job would wait for one unseen, while here each row
     * says at once why it failed), or when the system refuses the job.
     */
    fun userSync(
        context: Context,
        checkUpdates: Boolean,
        only: Set<String>? = null,
        force: Boolean = false,
    ): Boolean {
        val ctx = context.applicationContext
        if (Network.isValidated(ctx) && UpdateJob.scheduleUser(ctx, checkUpdates, only, force)) {
            return true
        }
        launchSync(ctx, checkUpdates, only, force)
        return false
    }

    /**
     * Writes apps.install = [ids] and apps.confirmed = true (the Recommended screen's button), then
     * installs and removes accordingly. The list is always written out, also when it equals the
     * catalog's defaults: an unset apps.install means that nothing was confirmed.
     *
     * @throws ConfigException when ichthys_config refuses the change
     */
    fun applySelection(context: Context, ids: Collection<String>) {
        AppsConfig.setInstall(ids, confirmed = true)
        if (!userSync(context, checkUpdates = false)) {
            // In-process only: a JobScheduler sync finishes the work if this process is killed.
            UpdateJob.scheduleSync(context, Trigger.USER, checkUpdates = false)
        }
    }

    /**
     * Drops [id] from apps.install; the sync then uninstalls it.
     *
     * @throws ConfigException when ichthys_config refuses the change
     */
    fun remove(context: Context, id: String) {
        synchronized(configLock) {
            val ids = AppsConfig.read(context).install ?: return
            AppsConfig.setInstall(ids.filter { it != id })
        }
        launchSync(context, checkUpdates = false)
    }

    /**
     * The app [id] names: a catalog entry, or an adopted app (by its package name). Null when it is
     * neither any more.
     */
    fun find(context: Context, id: String): CatalogApp? =
        Catalog.get()[id]
            ?: AppsConfig.read(context)
                .adopted
                .firstOrNull { it.packageName == id }
                ?.let { Adoption.app(context, it) }

    /**
     * Starts keeping the installed [packageName] updated from [source]: pins the certificate(s) it
     * is signed with right now (trust on first use: there is no pin from its developer to check
     * them against), adds it to apps.adopted and checks the source for an update.
     *
     * @throws ConfigException when the app isn't installed or ichthys_config refuses the change
     */
    fun adopt(context: Context, packageName: String, source: String) {
        val ctx = context.applicationContext
        val pins =
            CertVerifier.installedSigners(ctx, packageName)?.takeIf { it.isNotEmpty() }
                ?: throw ConfigException("$packageName is not installed")
        synchronized(configLock) {
            val others = AppsConfig.read(ctx).adopted.filter { it.packageName != packageName }
            AppsConfig.setAdopted(others + Adopted(packageName, source, pins))
        }
        // Whatever was recorded for an earlier adoption (another source) says nothing now.
        AppStore(ctx).forget(packageName)
        Log.i(TAG, "adopted $packageName from $source, pinned ${pins.map { it.take(16) }}")
        userSync(ctx, checkUpdates = true, only = setOf(packageName))
    }

    /**
     * Stops updating an adopted app: it leaves apps.adopted and stays installed as it is.
     *
     * @throws ConfigException when ichthys_config refuses the change
     */
    fun unadopt(context: Context, packageName: String) {
        val ctx = context.applicationContext
        synchronized(configLock) {
            val adopted = AppsConfig.read(ctx).adopted
            if (adopted.none { it.packageName == packageName }) return
            AppsConfig.setAdopted(adopted.filter { it.packageName != packageName })
        }
        AppStore(ctx).forget(packageName)
        workDir(ctx, packageName).deleteRecursively()
        Notifications.cancelProblem(ctx, packageName)
        _ops.update { it - packageName }
    }

    /**
     * One reconcile pass.
     *
     * @param only restrict installs/updates to these ids (removals always run)
     * @param force for [Trigger.USER] "Update now": download even if the source claims nothing
     *   changed (the versionCode comparison still applies)
     */
    suspend fun sync(
        context: Context,
        trigger: Trigger,
        checkUpdates: Boolean,
        only: Set<String>? = null,
        force: Boolean = false,
    ) {
        val ctx = context.applicationContext
        SourceRegistry.init(ctx)
        mutex.withLock {
            val catalog = Catalog.get()
            val state = AppsConfig.read(ctx)
            val automatic = trigger != Trigger.USER
            // Nothing happens by itself while the setup wizard is still up.
            if (automatic && !setupComplete(ctx)) return
            // The user's list. Unconfirmed, or apps.install unset: there is none to install or
            // remove by. The system apps and the adopted apps are updated regardless.
            val managed = state.managed(catalog)
            if (state.ready) removeDropped(ctx, catalog, managed)

            val targets =
                (managed.mapNotNull { catalog[it] } + catalog.bundled + Adoption.apps(ctx, state))
                    .filter { only == null || it.id in only }
            for (app in targets) set(app.id, OpState.Queued)
            val updates = checkUpdates && (state.autoUpdate || !automatic)
            var reachedAll = true
            for (app in targets) {
                // The lists may have changed since this pass read them (Remove, Stop updating, an
                // uninstall made elsewhere): what left them is not touched again.
                if (!stillWanted(ctx, app)) {
                    _ops.update { it - app.id }
                    continue
                }
                val installed = installedInfo(ctx, app.packageName)
                // Only an app of the user's list is ever installed. A system app or an adopted
                // app that is gone (disabled for this user, uninstalled) is left alone.
                if (installed == null && app.kind != Kind.APP) {
                    _ops.update { it - app.id }
                    continue
                }
                if (installed != null && !updates) {
                    _ops.update { it - app.id }
                    continue
                }
                if (automatic && !Network.isConnected(ctx)) {
                    _ops.update { it - app.id }
                    reachedAll = false
                    continue
                }
                if (automatic && state.wifiOnly && Network.isMetered(ctx)) {
                    set(app.id, OpState.WaitingForWifi)
                    reachedAll = false
                    continue
                }
                val reached =
                    installOrUpdate(
                        ctx,
                        app,
                        installed,
                        automatic = automatic,
                        notify = automatic || !uiVisible,
                        force = force,
                    )
                if (!reached) reachedAll = false
            }
            if (updates && only == null && reachedAll) {
                AppStore(ctx).lastUpdateCheck = System.currentTimeMillis()
            }
        }
    }

    private fun setupComplete(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.USER_SETUP_COMPLETE, 0) == 1

    /** Whether [app] is still to be handled, by the lists as they are now. */
    private fun stillWanted(ctx: Context, app: CatalogApp): Boolean =
        when (app.kind) {
            Kind.APP -> AppsConfig.read(ctx).install?.contains(app.id) == true
            Kind.BUNDLED -> true
            Kind.ADOPTED -> AppsConfig.read(ctx).adopted.any { it.packageName == app.id }
        }

    /**
     * Uninstalls apps whose ids were managed at the last pass but are no longer in apps.install
     * (the user unchecked or removed them, or ichthys.nix dropped them). Network-free; also run by
     * the settings watcher job.
     */
    suspend fun reconcileRemovals(context: Context) {
        val ctx = context.applicationContext
        mutex.withLock {
            val catalog = Catalog.get()
            val state = AppsConfig.read(ctx)
            if (state.ready) removeDropped(ctx, catalog, state.managed(catalog))
        }
    }

    /** Ids in apps.install that are not installed (the watcher then queues a network sync). */
    fun missing(context: Context): List<String> {
        val catalog = Catalog.get()
        return AppsConfig.read(context).managed(catalog).filter { id ->
            catalog[id]?.let { installedInfo(context, it.packageName) == null } == true
        }
    }

    private suspend fun removeDropped(ctx: Context, catalog: Catalog, managed: List<String>) {
        val store = AppStore(ctx)
        val dropped = store.managed - managed.toSet()
        for (id in dropped) {
            val app = catalog[id] ?: continue
            if (installedInfo(ctx, app.packageName) == null) {
                store.forget(id)
                // IchthysOS: and the half download kept for resuming (up to 600 MB) goes too.
                workDir(ctx, id).deleteRecursively()
                Notifications.cancelProblem(ctx, app)
                continue
            }
            // IchthysOS: a copy with another signer was installed from elsewhere (Termux from
            // GitHub, …); Ichthys Apps never managed it, so it stops tracking it and leaves the
            // app and its data alone.
            if (CertVerifier.installedMatches(ctx, app.packageName, app.certs) != true) {
                store.forget(id)
                workDir(ctx, id).deleteRecursively()
                Notifications.cancelProblem(ctx, app)
                continue
            }
            set(id, OpState.Uninstalling)
            when (val outcome = Installer.uninstall(ctx, app)) {
                Outcome.Success -> {
                    set(id, OpState.Removed)
                    store.forget(id)
                    workDir(ctx, id).deleteRecursively()
                    Notifications.cancelProblem(ctx, app)
                }
                is Outcome.PendingUser -> {
                    set(id, OpState.NeedsUser)
                    askUser(ctx, app, outcome.confirm, "${app.packageName}:uninstall")
                }
                is Outcome.Failure -> {
                    set(id, OpState.Failed(outcome.message))
                    if (store.setError(id, outcome.message) == 1) {
                        Notifications.failure(ctx, app, outcome.message, FailureKind.REMOVE)
                    }
                }
            }
        }
        store.managed = managed.toSet()
    }

    /**
     * @param automatic a background pass: it doesn't ask the same question twice, and leaves a
     *   large download for an unmetered network
     * @return false when the app was left for an unmetered network, true when it was dealt with
     *   (installed, up to date, or failed)
     */
    private suspend fun installOrUpdate(
        ctx: Context,
        app: CatalogApp,
        installed: PackageInfo?,
        automatic: Boolean,
        notify: Boolean,
        force: Boolean,
    ): Boolean {
        val store = AppStore(ctx)
        val work = workDir(ctx, app.id)
        // Whether the work directory outlives this attempt. It does while a download in it can
        // still be continued: until the files are complete, or turn out not to be needed.
        var keepWork = true
        try {
            set(app.id, OpState.Resolving)
            val pinned = CertVerifier.installedMatches(ctx, app.packageName, app.certs)
            if (installed != null && pinned == false) {
                throw CertMismatchException(
                    when (app.kind) {
                        Kind.APP ->
                            "the installed copy has another signer; uninstall it to let Ichthys " +
                                "Apps manage ${app.name}"
                        Kind.BUNDLED ->
                            "the installed copy isn't signed with the certificate the catalog pins"
                        Kind.ADOPTED ->
                            "the installed copy is no longer signed with the certificate pinned " +
                                "when it was adopted; stop updating it and adopt it again"
                    }
                )
            }
            val source = SourceRegistry.forSource(app.source)
            val metered = automatic && Network.isMetered(ctx)
            val device = DeviceSpec.of(ctx, saveData = metered)
            val found = source.resolve(app.spec, device)
            store.checked(app.id, found)
            val release = choose(found, installed, store.record(app.id))
            // IchthysOS: "Update now" (force) skips only the name and fingerprint guesses; a
            // versionCode the source states that isn't newer still means up to date, as the KDoc
            // of sync() says, so nothing is downloaded just to be refused.
            val upToDate =
                installed != null &&
                    if (force) release.versionCode?.let { it <= installed.longVersionCode } == true
                    else notNewer(release, installed, store.record(app.id))
            if (upToDate) {
                keepWork = false
                store.clearError(app.id)
                set(app.id, OpState.UpToDate)
                return true
            }
            // The install of this very release is waiting for the user's answer, or they said
            // no: downloading it again would only ask the same question. "Update now" asks again.
            if (
                automatic &&
                    release.fingerprint != null &&
                    release.fingerprint == store.askedRelease(app.id)
            ) {
                keepWork = false
                set(app.id, OpState.NeedsUser)
                return true
            }
            if (metered) {
                val size = downloadSize(release)
                if (size > LARGE_DOWNLOAD_BYTES) {
                    Log.i(TAG, "${app.id}: ${size shr 20} MB, left for an unmetered network")
                    set(app.id, OpState.WaitingForWifi)
                    UpdateJob.scheduleUnmetered(ctx)
                    return false
                }
            }

            prepareWork(work, release)
            // If this fails (the network went, the deadline passed), the next attempt continues
            // from the finished files and the partial one.
            val files =
                release.files.mapIndexed { i, f ->
                    val dest = File(work, "dl$i-" + Http.safeName(f.name))
                    // Finished by an earlier, interrupted attempt at this release.
                    if (dest.isFile) return@mapIndexed dest
                    Http.download(f, dest) { done, total ->
                        set(app.id, OpState.Downloading(done, total))
                    }
                }
            keepWork = false
            set(app.id, OpState.Verifying)
            val apks = source.unpack(app.spec, files, work, device)
            val base = CertVerifier.verify(ctx, apks, app)
            if (installed != null && base.versionCode <= installed.longVersionCode) {
                store.setFingerprint(app.id, release.fingerprint)
                set(app.id, OpState.UpToDate)
                return true
            }

            set(app.id, OpState.Installing)
            when (val outcome = Installer.install(ctx, app, apks)) {
                Outcome.Success -> {
                    Log.i(TAG, "${app.id}: installed ${base.versionName} (${base.versionCode})")
                    store.setFingerprint(app.id, release.fingerprint)
                    set(app.id, if (installed != null) OpState.Updated else OpState.Done)
                    Notifications.cancelProblem(ctx, app)
                }
                is Outcome.PendingUser -> {
                    // Not recorded as installed yet. Remembered as asked about, so that the next
                    // background pass doesn't download it again while the question is open.
                    set(app.id, OpState.NeedsUser)
                    store.setAskedRelease(app.id, release.fingerprint)
                    askUser(ctx, app, outcome.confirm, "${app.packageName}:${base.versionCode}")
                }
                is Outcome.Failure ->
                    fail(ctx, app, outcome.message, notify, kind = kindOf(installed))
            }
            return true
        } catch (e: CancellationException) {
            // Stopped from outside (the job's time is up, its network went): nothing that was
            // downloaded is thrown away, the rescheduled job picks it up.
            keepWork = true
            set(app.id, OpState.Interrupted)
            throw e
        } catch (e: CertMismatchException) {
            // Always worth telling: the source served something the catalog didn't pin.
            Log.w(TAG, "${app.id}: ${e.message}")
            fail(
                ctx,
                app,
                e.message ?: "signature mismatch",
                notify = true,
                kind = kindOf(installed),
            )
        } catch (e: IOException) {
            Log.w(TAG, "${app.id}: ${e.message}", e)
            fail(
                ctx,
                app,
                e.message ?: e.javaClass.simpleName,
                notify,
                patient = automatic && installed != null,
                kind = kindOf(installed),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "${app.id}: install crashed", e)
            fail(ctx, app, e.message ?: e.javaClass.simpleName, notify, kind = kindOf(installed))
        } finally {
            if (!keepWork) work.deleteRecursively()
        }
        return true
    }

    /**
     * How much [release] downloads, in bytes: what the source said, else what the server says
     * (0 for a file neither tells about).
     */
    private fun downloadSize(release: Release): Long =
        release.files.sumOf { f ->
            f.size
                ?: try {
                    Http.head(f.url, f.referer, f.userAgent).contentLength.coerceAtLeast(0L)
                } catch (e: IOException) {
                    0L
                }
        }

    private fun workDir(ctx: Context, id: String) = File(ctx.cacheDir, "work/$id")

    /**
     * Empties [work], unless it holds an interrupted attempt at this very release (same
     * fingerprint, same files): then its finished downloads are used as they are and the partial
     * one is resumed. Whatever is reused still has to pass [CertVerifier] like a fresh download.
     */
    private fun prepareWork(work: File, release: Release) {
        val marker = File(work, "release")
        val key =
            release.fingerprint?.let { fingerprint ->
                (listOf(fingerprint) + release.files.map { it.name }).joinToString("\n")
            }
        val same =
            key != null &&
                try {
                    marker.readText() == key
                } catch (e: IOException) {
                    false
                }
        if (same) return
        work.deleteRecursively()
        work.mkdirs()
        if (key != null) marker.writeText(key)
    }

    /**
     * The release to install: [found]'s beta for an app that follows betas (see the class comment),
     * else the stable one. Without a newer beta, also a beta follower gets the stable release.
     */
    fun choose(found: Release, installed: PackageInfo?, record: AppRecord): Release {
        val beta = found.beta ?: return found
        return if (followsBeta(record, installed?.longVersionCode, found.versionCode)) beta
        else found
    }

    /**
     * Whether an app gets pre-releases: the user asked for them, or the installed version
     * ([installedCode]) is newer than the newest stable one ([stableCode]).
     */
    fun followsBeta(record: AppRecord, installedCode: Long?, stableCode: Long?): Boolean =
        record.followBeta || onBeta(installedCode, stableCode)

    /** The installed version is newer than the newest stable release: a beta is installed. */
    fun onBeta(installedCode: Long?, stableCode: Long?): Boolean =
        installedCode != null && stableCode != null && installedCode > stableCode

    /** True when the release is provably the installed one (or one already found not newer). */
    private fun notNewer(release: Release, installed: PackageInfo, record: AppRecord): Boolean {
        release.versionCode?.let {
            return it <= installed.longVersionCode
        }
        val name = installed.versionName
        if (release.versionName != null && name != null && sameVersion(release.versionName, name)) {
            return true
        }
        return release.fingerprint != null && release.fingerprint == record.fingerprint
    }

    /** "v3.4.0" and "3.4.0" are the same version: sources name a version by its release tag. */
    private fun sameVersion(a: String, b: String): Boolean =
        a.removePrefix("v").removePrefix("V") == b.removePrefix("v").removePrefix("V")

    /** IchthysOS: an attempt on an app that isn't installed is an install, else an update. */
    private fun kindOf(installed: PackageInfo?): FailureKind =
        if (installed == null) FailureKind.INSTALL else FailureKind.UPDATE

    /**
     * @param patient a background update check that could not reach or read its source: see
     *   [FAILED_CHECKS_BEFORE_NOTICE]
     * @param kind IchthysOS: what failed, for the notification's title
     */
    private fun fail(
        ctx: Context,
        app: CatalogApp,
        message: String,
        notify: Boolean,
        patient: Boolean = false,
        kind: FailureKind = FailureKind.INSTALL,
    ) {
        set(app.id, OpState.Failed(message))
        // Failures notify once per distinct error, not on every pass that runs into it again.
        val repeats = AppStore(ctx).setError(app.id, message)
        if (notify && repeats == (if (patient) FAILED_CHECKS_BEFORE_NOTICE else 1)) {
            Notifications.failure(ctx, app, message, if (patient) FailureKind.CHECK else kind)
        }
    }

    /**
     * The system wants a confirmation: start it if the UI is up, else notify. The notification is
     * posted once per [what] (the package and version, or the uninstall), however many later passes
     * run into the same prompt; "Update now" in the app asks again.
     */
    private fun askUser(ctx: Context, app: CatalogApp, confirm: Intent?, what: String) {
        val first = AppStore(ctx).setAsked(app.id, what)
        if (confirm != null && uiVisible) {
            try {
                ctx.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "${app.id}: cannot start confirmation", e)
            }
        }
        if (first) {
            Notifications.userAction(ctx, app, confirm, uninstall = what.endsWith(":uninstall"))
        }
    }

    /** A PackageInstaller result nobody waited for (see [Installer]). */
    fun onLateResult(context: Context, appId: String, uninstall: Boolean, outcome: Outcome) {
        val app = find(context, appId) ?: return
        val store = AppStore(context)
        when (outcome) {
            Outcome.Success -> {
                set(appId, if (uninstall) OpState.Removed else OpState.Done)
                val asked = store.askedRelease(appId)
                when {
                    uninstall -> store.forget(appId)
                    // The user confirmed: the release they were asked about is the installed one.
                    asked != null -> store.setFingerprint(appId, asked)
                    else -> store.clearError(appId)
                }
                Notifications.cancelProblem(context, app)
            }
            is Outcome.PendingUser ->
                // The version isn't known here; a request already made for this app stands.
                askUser(context, app, outcome.confirm, store.asked(appId) ?: app.packageName)
            is Outcome.Failure ->
                if (outcome.status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // IchthysOS: the user said no to the system's confirmation. That is their
                    // answer, not a failure: no "install failed: aborted" notification or red row.
                    // The release stays recorded as asked about, so background passes don't ask
                    // again; "Update now" does.
                    Log.i(TAG, "$appId: declined by the user")
                    _ops.update { it - appId }
                    Notifications.cancelProblem(context, app)
                } else {
                    val kind =
                        when {
                            uninstall -> FailureKind.REMOVE
                            installedInfo(context, app.packageName) != null -> FailureKind.UPDATE
                            else -> FailureKind.INSTALL
                        }
                    fail(context, app, outcome.message, notify = true, kind = kind)
                }
        }
    }

    /**
     * An app was fully removed. If the user did it to a catalog app (its id is still in
     * apps.install), the id leaves apps.install, so it is not reinstalled; an adopted app leaves
     * apps.adopted.
     *
     * Called from a broadcast receiver, so it never waits for a running sync: the config change
     * takes only [configLock], and the sync looks at the lists again before each app.
     */
    fun onPackageRemoved(context: Context, packageName: String) {
        val ctx = context.applicationContext
        try {
            // No-op unless it is adopted.
            unadopt(ctx, packageName)
        } catch (e: ConfigException) {
            Log.w(TAG, "cannot drop $packageName from apps.adopted", e)
        }
        val app = Catalog.get().forPackage(packageName) ?: return
        try {
            synchronized(configLock) {
                val state = AppsConfig.read(ctx)
                val ids = state.install
                if (!state.ready || ids == null || app.id !in ids) return
                AppsConfig.setInstall(ids.filter { it != app.id })
            }
        } catch (e: ConfigException) {
            Log.w(TAG, "cannot drop ${app.id} from apps.install", e)
            return
        }
        AppStore(ctx).unmanage(app.id)
        set(app.id, OpState.Removed)
        Log.i(TAG, "${app.id} uninstalled outside Ichthys Apps; dropped from apps.install")
    }

    fun installedInfo(context: Context, packageName: String): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
}
