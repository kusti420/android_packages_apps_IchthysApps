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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.android.internal.R as IR
import org.ichthys.apps.R
import org.ichthys.apps.catalog.Catalog
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.config.AppsConfig
import org.ichthys.apps.sync.AppStore
import org.ichthys.apps.ui.MainActivity

/**
 * The only notifications Ichthys Apps posts: the first-run "Recommended apps" prompt, failed
 * installs/updates, installs that need the user's confirmation (once per package and version) and
 * the progress of the job that runs what the user asked for in the app. Successful background
 * updates stay silent.
 */
object Notifications {
    private const val TAG = "IchthysApps"
    private const val CHANNEL_SETUP = "setup"
    private const val CHANNEL_PROBLEMS = "problems"
    private const val CHANNEL_PROGRESS = "progress"
    private const val ID_RECOMMENDED = 1

    /** The user-initiated job's notification ([org.ichthys.apps.update.UpdateJob.JOB_USER]). */
    const val ID_PROGRESS = 2
    private const val ID_PROBLEM_BASE = 0x1000

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_SETUP,
                    ctx.getString(R.string.channel_setup),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply { description = ctx.getString(R.string.channel_setup_summary) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_PROBLEMS,
                    ctx.getString(R.string.channel_problems),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply { description = ctx.getString(R.string.channel_problems_summary) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_PROGRESS,
                    ctx.getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply { description = ctx.getString(R.string.channel_progress_summary) }
        )
    }

    private fun builder(ctx: Context, channel: String): Notification.Builder {
        ensureChannels(ctx)
        return Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_ichthys_apps)
            .setColor(ctx.getColor(IR.color.ichthys_accent))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
    }

    /**
     * First run: once per boot, after setup (USER_SETUP_COMPLETE) and until apps.confirmed with an
     * explicit apps.install, posts "Recommended apps", which opens the Recommended screen. Cancels
     * it once confirmed.
     */
    fun checkFirstRun(ctx: Context) {
        val cr = ctx.contentResolver
        if (AppsConfig.read(ctx).ready) {
            nm(ctx).cancel(ID_RECOMMENDED)
            return
        }
        if (Settings.Secure.getInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 1) return
        if (Catalog.get().recommended.isEmpty()) return
        val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0)
        val store = AppStore(ctx)
        if (store.recommendedPostedBoot == boot) return
        store.recommendedPostedBoot = boot
        val open =
            PendingIntent.getActivity(
                ctx,
                ID_RECOMMENDED,
                Intent(ctx, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_RECOMMENDED)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text = ctx.getString(R.string.notif_recommended_text)
        post(
            ctx,
            ID_RECOMMENDED,
            builder(ctx, CHANNEL_SETUP)
                .setContentTitle(ctx.getString(R.string.notif_recommended_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .build(),
        )
    }

    /**
     * IchthysOS: what a failure notification is about, so its title says what went wrong (an
     * update or a removal used to be titled "install failed").
     */
    enum class FailureKind {
        /** An app that isn't on the phone could not be installed. */
        INSTALL,
        /** A newer version could not be installed over the one on the phone. */
        UPDATE,
        /** A background update check kept failing; the installed app is fine. */
        CHECK,
        /** An app that left apps.install could not be uninstalled. */
        REMOVE,
    }

    fun failure(ctx: Context, app: CatalogApp, message: String, kind: FailureKind) {
        val title =
            when (kind) {
                FailureKind.INSTALL -> R.string.notif_failed_title
                FailureKind.UPDATE -> R.string.notif_update_failed_title
                FailureKind.CHECK -> R.string.notif_check_failed_title
                FailureKind.REMOVE -> R.string.notif_remove_failed_title
            }
        post(
            ctx,
            problemId(app),
            builder(ctx, CHANNEL_PROBLEMS)
                .setContentTitle(ctx.getString(title, app.name))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openInstalled(ctx, problemId(app)))
                .build(),
        )
    }

    /**
     * The ongoing notification of the user-initiated job: the app being worked on ([appName], null
     * between apps), what is being done to it ([stage], a status string; IchthysOS: so a plain
     * update check reads "Looking up the latest version", not just the title's "Installing") and
     * how far its download is ([percent], null when that isn't known).
     */
    fun progress(ctx: Context, appName: String?, percent: Int?, stage: Int? = null): Notification =
        builder(ctx, CHANNEL_PROGRESS)
            .setContentTitle(ctx.getString(R.string.notif_progress_title))
            .setContentText(
                if (appName != null && stage != null) {
                    ctx.getString(R.string.notif_progress_text, appName, ctx.getString(stage))
                } else {
                    appName
                }
            )
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openInstalled(ctx, ID_PROGRESS))
            .build()

    /**
     * [confirm] is PackageInstaller's confirmation activity, or null to just open the app. The
     * caller posts this once per package and version (AppStore.setAsked), not on every pass.
     *
     * @param uninstall IchthysOS: the confirmation is for removing the app, not installing it
     */
    fun userAction(ctx: Context, app: CatalogApp, confirm: Intent?, uninstall: Boolean = false) {
        val tap =
            if (confirm != null) {
                PendingIntent.getActivity(
                    ctx,
                    problemId(app),
                    Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                openInstalled(ctx, problemId(app))
            }
        post(
            ctx,
            problemId(app),
            builder(ctx, CHANNEL_PROBLEMS)
                .setContentTitle(ctx.getString(R.string.notif_user_action_title, app.name))
                .setContentText(
                    ctx.getString(
                        if (uninstall) R.string.notif_user_action_remove_text
                        else R.string.notif_user_action_text
                    )
                )
                .setContentIntent(tap)
                .build(),
        )
    }

    fun cancelProblem(ctx: Context, app: CatalogApp) = cancelProblem(ctx, app.id)

    /** By app id: a catalog id, or an adopted app's package name. */
    fun cancelProblem(ctx: Context, id: String) = nm(ctx).cancel(problemId(id))

    private fun openInstalled(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            requestCode,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_INSTALLED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun problemId(app: CatalogApp) = problemId(app.id)

    private fun problemId(id: String) = ID_PROBLEM_BASE + (id.hashCode() and 0xfff)

    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            nm(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked by the user.
            Log.w(TAG, "notification $id not posted", e)
        }
    }
}
