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

package org.ichthys.apps.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.ichthys.apps.catalog.CatalogApp

/** How a PackageInstaller install or uninstall ended. */
sealed interface Outcome {
    data object Success : Outcome

    /** The system wants the user to confirm (e.g. another installer owns updates of the app). */
    data class PendingUser(val confirm: Intent?) : Outcome

    data class Failure(val message: String, val status: Int = PackageInstaller.STATUS_FAILURE) :
        Outcome
}

/**
 * PackageInstaller sessions. Ichthys Apps holds INSTALL_PACKAGES and requests update ownership, so
 * installs and later updates of its apps complete without a prompt. Results arrive at
 * [InstallResultReceiver] through an explicit mutable PendingIntent and are handed back to the
 * suspended caller; results nobody waits for any more (the process restarted, a confirmation the
 * user gave later) go to [org.ichthys.apps.sync.AppsManager.onLateResult].
 */
object Installer {
    private const val TAG = "IchthysApps"
    const val ACTION_INSTALL_STATUS = "org.ichthys.apps.action.INSTALL_STATUS"
    const val ACTION_UNINSTALL_STATUS = "org.ichthys.apps.action.UNINSTALL_STATUS"
    const val EXTRA_APP_ID = "org.ichthys.apps.extra.APP_ID"
    const val EXTRA_KEY = "org.ichthys.apps.extra.KEY"
    private const val TIMEOUT_MS = 10 * 60_000L

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()

    /**
     * Installs [apks] (base first, then splits) as one session: MODE_FULL_INSTALL, install reason
     * USER, update ownership requested, no user action required.
     */
    suspend fun install(context: Context, app: CatalogApp, apks: List<File>): Outcome {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setRequestUpdateOwnership(true)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                setSize(apks.sumOf { it.length() })
            }
        val sessionId = installer.createSession(params)
        val key = "install:$sessionId"
        val result = CompletableDeferred<Outcome>()
        waiters[key] = result
        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { i, apk ->
                    val name = if (i == 0) "base.apk" else apk.name
                    apk.inputStream().use { input ->
                        session.openWrite(name, 0, apk.length()).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    }
                }
                session.commit(statusSender(context, ACTION_INSTALL_STATUS, app.id, key, sessionId))
            }
            return withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: Outcome.Failure("no result from the package installer")
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (ignored: Exception) {}
            throw e
        } finally {
            waiters.remove(key)
        }
    }

    /** Uninstalls [app] for this user (silent with DELETE_PACKAGES). */
    suspend fun uninstall(context: Context, app: CatalogApp): Outcome {
        val key = "uninstall:${app.packageName}"
        val result = CompletableDeferred<Outcome>()
        waiters[key] = result
        try {
            context.packageManager.packageInstaller.uninstall(
                VersionedPackage(app.packageName, PackageManager.VERSION_CODE_HIGHEST),
                statusSender(
                    context,
                    ACTION_UNINSTALL_STATUS,
                    app.id,
                    key,
                    app.packageName.hashCode(),
                ),
            )
            return withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: Outcome.Failure("no result from the package installer")
        } finally {
            waiters.remove(key)
        }
    }

    /**
     * Called by [InstallResultReceiver]. Returns false when nobody is waiting for [key] (the caller
     * then handles the outcome itself).
     */
    fun deliver(key: String, outcome: Outcome): Boolean {
        val waiter = waiters[key] ?: return false
        Log.i(TAG, "$key: $outcome")
        return waiter.complete(outcome)
    }

    /** Maps a PackageInstaller status broadcast to an [Outcome]. */
    fun outcomeOf(intent: Intent): Outcome {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> Outcome.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Outcome.PendingUser(
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                )
            else ->
                Outcome.Failure(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: statusName(status),
                    status,
                )
        }
    }

    private fun statusName(status: Int): String =
        when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicts with the installed app"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this phone"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
            else -> "failed ($status)"
        }

    private fun statusSender(
        context: Context,
        action: String,
        appId: String,
        key: String,
        requestCode: Int,
    ): IntentSender {
        val intent =
            Intent(context, InstallResultReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_APP_ID, appId)
                .putExtra(EXTRA_KEY, key)
        // Mutable: PackageInstaller fills in the status extras. Explicit, so that is safe.
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            .intentSender
    }
}
