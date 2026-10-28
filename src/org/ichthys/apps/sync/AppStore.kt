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
import android.content.SharedPreferences
import org.ichthys.apps.source.Release

/** What Ichthys Apps remembers per app between runs (not configuration: that is ichthys.nix). */
data class AppRecord(
    /** When the source was last asked for a new version (millis), 0 if never. */
    val lastCheck: Long,
    /** The newest version name the source reported, if it reports one. */
    val latestVersion: String?,
    /** Its versionCode, if the source reports one. */
    val latestCode: Long?,
    /** The newer pre-release the source reported ([Release.beta]): its version name, or null. */
    val latestBeta: String?,
    val latestBetaCode: Long?,
    /** The user asked for this app's pre-releases too (Installed › Follow beta). */
    val followBeta: Boolean,
    /** [org.ichthys.apps.source.Release.fingerprint] of the release installed or found old. */
    val fingerprint: String?,
    /** The last failure, cleared by a success. */
    val lastError: String?,
)

/** Small SharedPreferences store (credential-encrypted storage; all callers run unlocked). */
class AppStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("apps_state", Context.MODE_PRIVATE)

    /**
     * The ids that were managed at the last reconcile. An id that leaves apps.install is
     * uninstalled once, by comparing against this.
     */
    var managed: Set<String>
        get() = prefs.getStringSet(KEY_MANAGED, null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_MANAGED, v).apply()

    /** Settings.Global.BOOT_COUNT when the Recommended notification was last posted. */
    var recommendedPostedBoot: Int
        get() = prefs.getInt(KEY_RECOMMENDED_BOOT, -1)
        set(v) = prefs.edit().putInt(KEY_RECOMMENDED_BOOT, v).apply()

    /**
     * When a pass last asked every managed app's source for updates (millis), 0 if never. A reboot
     * soon after doesn't ask them all again.
     */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    /** The notification permission was asked for once; a denial is not asked about again. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, v).apply()

    fun record(id: String) =
        AppRecord(
            lastCheck = prefs.getLong("$id.lastCheck", 0L),
            latestVersion = prefs.getString("$id.latest", null),
            latestCode = prefs.getLong("$id.latestCode", -1L).takeIf { it >= 0 },
            latestBeta = prefs.getString("$id.beta", null),
            latestBetaCode = prefs.getLong("$id.betaCode", -1L).takeIf { it >= 0 },
            followBeta = prefs.getBoolean("$id.followBeta", false),
            fingerprint = prefs.getString("$id.fingerprint", null),
            lastError = prefs.getString("$id.error", null),
        )

    /** The source was asked and answered [release]. */
    fun checked(id: String, release: Release) {
        val edit = prefs.edit().putLong("$id.lastCheck", System.currentTimeMillis())
        if (release.versionName != null) edit.putString("$id.latest", release.versionName)
        edit.putCode("$id.latestCode", release.versionCode)
        // A source that couldn't look for a beta this time leaves the last answer standing.
        if (release.betaChecked) {
            val beta = release.beta
            if (beta?.versionName != null) edit.putString("$id.beta", beta.versionName)
            else edit.remove("$id.beta")
            edit.putCode("$id.betaCode", beta?.versionCode)
        }
        edit.apply()
    }

    fun setFollowBeta(id: String, on: Boolean) {
        prefs.edit().putBoolean("$id.followBeta", on).apply()
    }

    private fun SharedPreferences.Editor.putCode(key: String, code: Long?) =
        if (code != null) putLong(key, code) else remove(key)

    fun setFingerprint(id: String, fingerprint: String?) {
        prefs
            .edit()
            .putString("$id.fingerprint", fingerprint)
            .remove("$id.error")
            .remove("$id.errorRepeats")
            .remove("$id.asked")
            .remove("$id.askedRelease")
            .apply()
    }

    /**
     * Records [error]; returns how many attempts in a row have now ended in it (1: it is new, or
     * differs from the last one).
     */
    fun setError(id: String, error: String): Int {
        val same = prefs.getString("$id.error", null) == error
        val repeats = if (same) prefs.getInt("$id.errorRepeats", 1) + 1 else 1
        prefs.edit().putString("$id.error", error).putInt("$id.errorRepeats", repeats).apply()
        return repeats
    }

    /** The app is fine again: forgets the last error and the confirmation it was waiting for. */
    fun clearError(id: String) {
        prefs
            .edit()
            .remove("$id.error")
            .remove("$id.errorRepeats")
            .remove("$id.asked")
            .remove("$id.askedRelease")
            .apply()
    }

    /** What the user was last asked to confirm for [id] (see [setAsked]), if still open. */
    fun asked(id: String): String? = prefs.getString("$id.asked", null)

    /**
     * Records that the user is being asked to confirm [what] (a package and version, or an
     * uninstall); returns true when that differs from the last request (worth a notification).
     */
    fun setAsked(id: String, what: String): Boolean {
        val changed = asked(id) != what
        if (changed) prefs.edit().putString("$id.asked", what).apply()
        return changed
    }

    /**
     * The [org.ichthys.apps.source.Release.fingerprint] of the release whose install is waiting
     * for the user's confirmation, or was declined. A background pass that finds the same release
     * again leaves it at that instead of downloading it once more to ask the same question.
     */
    fun askedRelease(id: String): String? = prefs.getString("$id.askedRelease", null)

    /**
     * IchthysOS: the release whose signature check or install failed (its fingerprint, or its
     * versionCode), so the background pass doesn't download the same failure again.
     */
    fun rejectedRelease(id: String): String? = prefs.getString("$id.rejectedRelease", null)

    fun setRejectedRelease(id: String, key: String?) {
        prefs.edit().putString("$id.rejectedRelease", key).apply()
    }

    fun setAskedRelease(id: String, fingerprint: String?) {
        prefs.edit().putString("$id.askedRelease", fingerprint).apply()
    }

    /**
     * The certificate(s) pinned for an adopted app whose apps.adopted entry carries none (written
     * by hand): the ones it was signed with when Ichthys Apps first saw the entry.
     */
    fun adoptedPins(packageName: String): Set<String> =
        prefs.getStringSet("$packageName.pins", null)?.toSet() ?: emptySet()

    fun setAdoptedPins(packageName: String, pins: Set<String>) {
        prefs.edit().putStringSet("$packageName.pins", pins).apply()
    }

    fun forget(id: String) {
        prefs
            .edit()
            .remove("$id.lastCheck")
            .remove("$id.latest")
            .remove("$id.latestCode")
            .remove("$id.beta")
            .remove("$id.betaCode")
            .remove("$id.followBeta")
            .remove("$id.fingerprint")
            .remove("$id.error")
            .remove("$id.errorRepeats")
            .remove("$id.asked")
            .remove("$id.askedRelease")
            .remove("$id.pins")
            .apply()
    }

    /**
     * [id] was uninstalled outside Ichthys Apps: it stops being managed, so a later pass neither
     * misses it nor uninstalls a copy the user installs again.
     */
    fun unmanage(id: String) {
        synchronized(LOCK) { managed = managed - id }
        forget(id)
    }

    private companion object {
        const val KEY_MANAGED = "managed"
        const val KEY_RECOMMENDED_BOOT = "recommended_boot"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

        /** Serializes read-modify-writes of [managed] (one prefs file per process). */
        val LOCK = Any()
    }
}
