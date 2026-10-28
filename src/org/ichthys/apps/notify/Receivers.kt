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

package org.ichthys.apps.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ichthys.apps.sync.AppStore
import org.ichthys.apps.sync.AppsManager
import org.ichthys.apps.sync.Trigger
import org.ichthys.apps.update.UpdateJob

private const val TAG = "IchthysApps"

/** A reboot asks the sources for updates again only when the last full check is older than this. */
private val BOOT_RECHECK_MS = TimeUnit.DAYS.toMillis(1)
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] off the main thread while keeping the broadcast alive (goAsync). */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "receiver work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * BOOT_COMPLETED (the user is unlocked by then) and MY_PACKAGE_REPLACED: (re)schedules the daily
 * job and the settings watcher, queues the boot-time sync (missing installs, under the network
 * constraint; updates too, unless every source was asked within the last day, e.g. by the daily
 * job) and posts the first-run notification when due. Before setup completes the watcher's
 * USER_SETUP_COMPLETE trigger posts it instead (USER_UNLOCKED never reaches manifest receivers, so
 * BOOT_COMPLETED is the unlock hook).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UpdateJob.scheduleAll(ctx)
                // Negative when the clock was set back: then check, too.
                val age = System.currentTimeMillis() - AppStore(ctx).lastUpdateCheck
                val checkedRecently = age in 0 until BOOT_RECHECK_MS
                UpdateJob.scheduleSync(ctx, Trigger.BOOT, checkUpdates = !checkedRecently)
                async { Notifications.checkFirstRun(ctx) }
            }
        }
    }
}

/**
 * PACKAGE_FULLY_REMOVED: a catalog app the user uninstalled outside Ichthys Apps leaves
 * apps.install (through ichthys_config), so the next sync doesn't reinstall it; an adopted app
 * that was uninstalled leaves apps.adopted. Removals Ichthys Apps made itself are already out of
 * the list and are ignored. The broadcast is only kept for that
 * one config write: it does not wait for a sync that is running.
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val ctx = context.applicationContext
        async { AppsManager.onPackageRemoved(ctx, pkg) }
    }
}
