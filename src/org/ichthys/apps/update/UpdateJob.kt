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

package org.ichthys.apps.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ichthys.apps.R
import org.ichthys.apps.config.AppsConfig
import org.ichthys.apps.notify.Notifications
import org.ichthys.apps.sync.AppsManager
import org.ichthys.apps.sync.OpState
import org.ichthys.apps.sync.Trigger

/**
 * Ichthys Apps' background work, all on JobScheduler:
 * - [JOB_DAILY]: periodic (1 day, persisted, battery not low, unmetered network with
 *   apps.wifiOnly): installs what is missing and, with apps.autoUpdate, updates silently.
 * - [JOB_SYNC]: one-off under a network constraint, queued on boot ("also run on boot", with
 *   updates unless a full check ran within the last day), after a config change that leaves apps
 *   missing, and as a backstop when the Recommended screen's Install has to run in-process.
 * - [JOB_USER]: what the user asked for in the app (Install, Update now, Check for updates), as a
 *   user-initiated data-transfer job: it starts at once, is not cut off after the usual ten minutes
 *   and keeps running, under its progress notification, when the user leaves the app. Each request
 *   is a work item; they run in order within one job.
 * - [JOB_UNMETERED]: one-off under an unmetered network, queued when a background pass on a
 *   metered one left a large download (over [AppsManager.LARGE_DOWNLOAD_BYTES]) for later.
 * - [JOB_WATCH]: content-triggered on the apps.* Settings.Secure keys and USER_SETUP_COMPLETE.
 *   Uninstalls dropped ids, queues installs, re-fits the daily job to apps.wifiOnly and posts or
 *   cancels the first-run notification. Content-trigger jobs can't be persisted and fire once, so
 *   it is rescheduled after every run and on boot.
 *
 * A job the system stops is cancelled where it is and asks to be rescheduled. Nothing it downloaded
 * is deleted for that: the next run continues from the files in the cache
 * ([AppsManager]'s install).
 */
class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        // A user-initiated job must show its notification within seconds of starting.
        if (params.jobId == JOB_USER) showProgress(params, null, null)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var finished = false
                try {
                    when (params.jobId) {
                        JOB_DAILY,
                        JOB_UNMETERED -> AppsManager.sync(ctx, Trigger.DAILY, checkUpdates = true)
                        JOB_SYNC -> {
                            val trigger =
                                params.extras.getString(EXTRA_TRIGGER)?.let {
                                    runCatching { Trigger.valueOf(it) }.getOrNull()
                                } ?: Trigger.CONFIG
                            val updates = params.extras.getBoolean(EXTRA_UPDATES, false)
                            AppsManager.sync(ctx, trigger, checkUpdates = updates)
                        }
                        JOB_WATCH -> onSettingsChanged(ctx)
                        JOB_USER -> runUser(ctx, params)
                    }
                    // A job of work items ends by itself once dequeueWork() has returned null.
                    finished = params.jobId != JOB_USER
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "job ${params.jobId} failed", e)
                    finished = true
                } finally {
                    // IchthysOS: only this instance's entry. A replaced pass finishing late used
                    // to remove its replacement's, which a later stop then couldn't cancel.
                    running.remove(params.jobId, coroutineContext[Job])
                    if (finished) jobFinished(params, false)
                    if (params.jobId == JOB_WATCH) scheduleWatch(ctx)
                }
            }
        running[params.jobId] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Rescheduling a job id replaces (stops) a running instance; the replacement will run
        // anyway, so let this pass finish instead of cutting an install short.
        if (params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP) return false
        // The pass stops where it is. Its finished downloads and the partial one stay in the cache
        // for the rescheduled run; only AppsManager deletes them, once they are installed or stale.
        running.remove(params.jobId)?.cancel()
        return params.jobId != JOB_WATCH
    }

    /**
     * [JOB_USER]: runs the queued requests in order, each as one sync. A request that was cut short
     * is delivered again when the job is rescheduled. The notification names the app being worked
     * on and follows its download.
     */
    private suspend fun runUser(ctx: Context, params: JobParameters) = coroutineScope {
        // Looked up once per app, not on every progress tick.
        val names = HashMap<String, String?>()
        val progress = launch {
            AppsManager.ops
                .map { ops ->
                    ops.entries
                        .firstOrNull { it.value.active }
                        ?.let { (id, op) ->
                            val fraction = (op as? OpState.Downloading)?.fraction
                            val name = names.getOrPut(id) { AppsManager.find(ctx, id)?.name }
                            Triple(name, fraction?.let { (it * 100).toInt() }, stageOf(op))
                        }
                }
                .distinctUntilChanged()
                .conflate()
                .collect { current ->
                    showProgress(params, current?.first, current?.second, current?.third)
                    delay(PROGRESS_INTERVAL_MS)
                }
        }
        try {
            while (true) {
                val work = params.dequeueWork() ?: break
                val extras = work.extras
                try {
                    AppsManager.sync(
                        ctx,
                        Trigger.USER,
                        checkUpdates = extras.getBoolean(EXTRA_UPDATES, false),
                        only = extras.getStringArray(EXTRA_ONLY)?.toSet(),
                        force = extras.getBoolean(EXTRA_FORCE, false),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "user sync failed", e)
                }
                params.completeWork(work)
            }
        } finally {
            progress.cancel()
        }
    }

    /** IchthysOS: the status line for the app being worked on, as its row on screen shows it. */
    private fun stageOf(op: OpState): Int? =
        when (op) {
            OpState.Resolving -> R.string.status_resolving
            is OpState.Downloading -> R.string.status_downloading
            OpState.Verifying -> R.string.status_verifying
            OpState.Installing -> R.string.status_installing
            OpState.Uninstalling -> R.string.status_uninstalling
            else -> null
        }

    private fun showProgress(
        params: JobParameters,
        app: String?,
        percent: Int?,
        stage: Int? = null,
    ) {
        try {
            setNotification(
                params,
                Notifications.ID_PROGRESS,
                Notifications.progress(this, app, percent, stage),
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "job notification not set", e)
        }
    }

    private suspend fun onSettingsChanged(ctx: Context) {
        Notifications.checkFirstRun(ctx)
        fitDaily(ctx)
        AppsManager.reconcileRemovals(ctx)
        if (AppsManager.missing(ctx).isNotEmpty()) {
            scheduleSync(ctx, Trigger.CONFIG, checkUpdates = false)
        }
    }

    companion object {
        private const val TAG = "IchthysApps"
        const val JOB_DAILY = 0x1a01
        const val JOB_SYNC = 0x1a02
        const val JOB_WATCH = 0x1a03
        const val JOB_USER = 0x1a04
        const val JOB_UNMETERED = 0x1a05
        private const val EXTRA_TRIGGER = "trigger"
        private const val EXTRA_UPDATES = "updates"
        private const val EXTRA_ONLY = "only"
        private const val EXTRA_FORCE = "force"

        /** The progress notification is refreshed no more often than this. */
        private const val PROGRESS_INTERVAL_MS = 1_000L

        private fun js(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

        private fun component(ctx: Context) = ComponentName(ctx, UpdateJob::class.java)

        private fun networkType(ctx: Context, trigger: Trigger): Int =
            if (trigger != Trigger.USER && AppsConfig.read(ctx).wifiOnly) {
                JobInfo.NETWORK_TYPE_UNMETERED
            } else {
                JobInfo.NETWORK_TYPE_ANY
            }

        /** Daily job (if missing or on the wrong network constraint) and the settings watcher. */
        fun scheduleAll(ctx: Context) {
            fitDaily(ctx)
            if (js(ctx).getPendingJob(JOB_WATCH) == null) scheduleWatch(ctx)
        }

        private fun fitDaily(ctx: Context) {
            val want = networkType(ctx, Trigger.DAILY)
            val pending = js(ctx).getPendingJob(JOB_DAILY)
            if (pending != null && pending.networkType == want) return
            val info =
                JobInfo.Builder(JOB_DAILY, component(ctx))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                    .setRequiredNetworkType(want)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
            js(ctx).schedule(info)
        }

        /** Queues a one-off sync once a suitable network is up. Pending update requests merge. */
        fun scheduleSync(ctx: Context, trigger: Trigger, checkUpdates: Boolean) {
            val pending = js(ctx).getPendingJob(JOB_SYNC)
            val updates = checkUpdates || pending?.extras?.getBoolean(EXTRA_UPDATES, false) == true
            val extras =
                PersistableBundle().apply {
                    putString(EXTRA_TRIGGER, trigger.name)
                    putBoolean(EXTRA_UPDATES, updates)
                }
            val info =
                JobInfo.Builder(JOB_SYNC, component(ctx))
                    .setRequiredNetworkType(networkType(ctx, trigger))
                    .setExtras(extras)
                    .setPersisted(true)
                    .build()
            js(ctx).schedule(info)
        }

        /**
         * Queues a pass for when an unmetered network is up: a background pass on a metered one
         * left a large download for it. Once is enough, whatever number of apps wait.
         */
        fun scheduleUnmetered(ctx: Context) {
            if (js(ctx).getPendingJob(JOB_UNMETERED) != null) return
            val info =
                JobInfo.Builder(JOB_UNMETERED, component(ctx))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .build()
            js(ctx).schedule(info)
        }

        /**
         * Queues a sync the user asked for on [JOB_USER] (see the class comment). The app must be
         * in the foreground. Returns false when the system doesn't take the job; the caller then
         * syncs in-process.
         */
        fun scheduleUser(
            ctx: Context,
            checkUpdates: Boolean,
            only: Set<String>?,
            force: Boolean,
        ): Boolean {
            val extras =
                PersistableBundle().apply {
                    putBoolean(EXTRA_UPDATES, checkUpdates)
                    putBoolean(EXTRA_FORCE, force)
                    if (only != null) putStringArray(EXTRA_ONLY, only.toTypedArray())
                }
            return try {
                // The same JobInfo every time, so a request made while the job runs joins its
                // queue instead of restarting it. User actions ignore apps.wifiOnly.
                val info =
                    JobInfo.Builder(JOB_USER, component(ctx))
                        .setUserInitiated(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build()
                val work = JobWorkItem.Builder().setExtras(extras).build()
                js(ctx).enqueue(info, work) == JobScheduler.RESULT_SUCCESS
            } catch (e: RuntimeException) {
                Log.w(TAG, "user-initiated job not scheduled", e)
                false
            }
        }

        fun scheduleWatch(ctx: Context) {
            val b = JobInfo.Builder(JOB_WATCH, component(ctx))
            for (key in AppsConfig.KEYS + Settings.Secure.USER_SETUP_COMPLETE) {
                b.addTriggerContentUri(JobInfo.TriggerContentUri(Settings.Secure.getUriFor(key), 0))
            }
            b.setTriggerContentUpdateDelay(500)
            b.setTriggerContentMaxDelay(3_000)
            js(ctx).schedule(b.build())
        }
    }
}
