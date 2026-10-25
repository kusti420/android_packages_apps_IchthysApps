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

package org.ichthys.apps.source

import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.io.File
import java.io.IOException

/**
 * One downloader, selected by the scheme of a catalog `source` ("<scheme>:<spec>"). Implementations
 * are stateless and blocking; [org.ichthys.apps.sync.AppsManager] calls them on an IO thread.
 *
 * To add one: implement this in its own file and register it in [SourceRegistry] (and in
 * IchthysPrebuilts/gen_catalog.py's SCHEMES, so catalog.list may use it).
 */
interface Source {
    /** The catalog scheme this handles, e.g. "github". */
    val scheme: String

    /**
     * Looks up the newest release for [spec] (the part after "<scheme>:"), as cheaply as the source
     * allows: ideally without downloading the APK itself.
     *
     * @throws IOException on network or parse errors (the caller reports and retries later)
     */
    fun resolve(spec: String, device: DeviceSpec): Release

    /**
     * Turns the downloaded [files] (in [Release.files] order) into the APKs of one install session
     * (base first, then splits). The default keeps them as they are; bundle formats (XAPK, a
     * release zip) unpack here, into [workDir], keeping only what [spec] and [device] ask for.
     */
    fun unpack(spec: String, files: List<File>, workDir: File, device: DeviceSpec): List<File> =
        files
}

/**
 * The newest stable release a [Source] found. Any of the version fields may be unknown; then the
 * APK is downloaded and its versionCode compared before installing.
 *
 * @property fingerprint identifies exactly this release (asset id + date, file name + size …). If
 *   it equals the fingerprint of the release last installed or found not newer, nothing changed and
 *   nothing is downloaded.
 * @property beta a pre-release newer than this one (F-Droid's Beta channel, a GitHub
 *   pre-release), when the source has one. Shown, but only installed for an app that follows
 *   betas ([org.ichthys.apps.sync.AppsManager]).
 * @property betaChecked whether the source looked for one: false when it could not this time (a
 *   pass sparing mobile data, GitHub's website fallback), so a null [beta] says nothing.
 */
data class Release(
    val versionName: String?,
    val versionCode: Long?,
    val fingerprint: String?,
    val files: List<RemoteFile>,
    val beta: Release? = null,
    val betaChecked: Boolean = true,
)

/**
 * One file to download.
 *
 * @property sha256 expected digest (lower-case hex), when the source publishes one
 * @property httpsOnly refuse cleartext http, also when a redirect leads to it
 * @property size the file's length in bytes, when the source says; null when only the server
 *   can tell (the caller then asks it before a download that may be too large for mobile data)
 */
data class RemoteFile(
    val url: String,
    val name: String,
    val referer: String? = null,
    val sha256: String? = null,
    val userAgent: String? = null,
    val httpsOnly: Boolean = false,
    val size: Long? = null,
)

/** What split APKs and versions this phone needs. */
data class DeviceSpec(
    /** Build.SUPPORTED_ABIS, most preferred first ("arm64-v8a", "armeabi-v7a"). */
    val abis: List<String>,
    val densityDpi: Int,
    /** Language codes of the user's locales, most preferred first ("en", "et"). */
    val languages: List<String>,
    val sdk: Int,
    /**
     * A background pass on a metered network: a source spares what it can do without (F-Droid
     * keeps a recent index instead of downloading the day's new one).
     */
    val saveData: Boolean = false,
) {
    companion object {
        fun of(context: Context, saveData: Boolean = false): DeviceSpec {
            val locales = LocaleList.getDefault()
            val langs = LinkedHashSet<String>()
            for (i in 0 until locales.size()) langs += locales[i].language
            return DeviceSpec(
                abis = Build.SUPPORTED_ABIS.toList(),
                densityDpi = context.resources.displayMetrics.densityDpi,
                languages = langs.toList(),
                sdk = Build.VERSION.SDK_INT,
                saveData = saveData,
            )
        }
    }
}
