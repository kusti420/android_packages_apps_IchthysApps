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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.IOException
import org.ichthys.apps.catalog.Catalog
import org.ichthys.apps.catalog.CatalogApp
import org.ichthys.apps.catalog.Kind
import org.ichthys.apps.config.Adopted
import org.ichthys.apps.config.AppsState
import org.ichthys.apps.install.CertVerifier
import org.ichthys.apps.source.DeviceSpec
import org.ichthys.apps.source.FdroidSource
import org.ichthys.apps.source.SourceRegistry

/**
 * A user app Ichthys Apps doesn't handle: installed from a store, a browser download, adb
 * (Installed › Other apps).
 *
 * @property installer the label of the app that installed it, when the system knows one
 */
data class OtherApp(val packageName: String, val label: String, val installer: String?)

/** What looking an installed app up on F-Droid came to. */
sealed interface SourceLookup {
    /** F-Droid has it, built with the key the installed copy is signed with. */
    data class Found(val source: String) : SourceLookup

    /** F-Droid has it, but signs it with another key: its builds can't update this copy. */
    data object OtherSigner : SourceLookup

    data object NotFound : SourceLookup

    /** The index could not be fetched or read. */
    data class Failed(val message: String) : SourceLookup
}

/**
 * Adopting apps: stores are for finding apps, Ichthys Apps is what updates them. An app installed
 * from elsewhere is adopted with a source to update it from (F-Droid when it has the same build,
 * else one the user names) and pinned to the certificate it is signed with at that moment: trust on
 * first use, since no pin from its developer ships with the ROM. From then on only updates signed
 * by that certificate install. See [AppsManager.adopt].
 */
object Adoption {
    private val GITHUB_URL =
        Regex(
            "^https?://(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:[/?#].*)?$",
            RegexOption.IGNORE_CASE,
        )
    private val GITLAB_URL =
        Regex(
            "^https?://gitlab\\.com/([\\w.-]+(?:/[\\w.-]+)+?)(?:\\.git)?(?:/-/.*)?/?$",
            RegexOption.IGNORE_CASE,
        )
    private val REPO = Regex("^[\\w.-]+/[\\w.-]+$")

    /**
     * The adopted apps as [AppsManager] handles them. An entry without a pin gets the installed
     * copy's certificate pinned now (and remembered); one that has neither a pin nor an installed
     * copy is left out, as there is nothing to hold an update to.
     */
    fun apps(context: Context, state: AppsState): List<CatalogApp> =
        state.adopted.mapNotNull { app(context, it) }

    fun app(context: Context, adopted: Adopted): CatalogApp? {
        val pkg = adopted.packageName
        val pm = context.packageManager
        val info =
            try {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        val store = AppStore(context)
        var pins = adopted.certs.ifEmpty { store.adoptedPins(pkg) }
        if (pins.isEmpty()) {
            pins = CertVerifier.installedSigners(context, pkg).orEmpty()
            if (pins.isEmpty()) return null
            store.setAdoptedPins(pkg, pins)
        }
        return CatalogApp(
            id = pkg,
            kind = Kind.ADOPTED,
            packageName = pkg,
            name = info?.loadLabel(pm)?.toString() ?: pkg,
            category = "",
            default = false,
            source = adopted.source,
            certs = pins,
            setup = emptyList(),
            description = "",
        )
    }

    /**
     * The user apps that could be adopted: not system apps, not adopted already, and not the
     * catalog's own apps (those are picked under Recommended; a copy of one signed by somebody
     * else is listed here, as the catalog entry can't update it).
     */
    fun others(context: Context, catalog: Catalog, state: AppsState): List<OtherApp> {
        val pm = context.packageManager
        val adopted = state.adopted.map { it.packageName }.toSet()
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .asSequence()
            .filter { (it.flags and system) == 0 }
            .filter { it.packageName != context.packageName && it.packageName !in adopted }
            .filter { ai ->
                val entry = catalog.forPackage(ai.packageName) ?: return@filter true
                CertVerifier.installedMatches(context, ai.packageName, entry.certs) != true
            }
            .map { ai ->
                OtherApp(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString(),
                    installer = installerLabel(pm, ai.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun installerLabel(pm: PackageManager, packageName: String): String? =
        try {
            pm.getInstallSourceInfo(packageName).installingPackageName?.let { installer ->
                try {
                    pm.getApplicationInfo(installer, PackageManager.ApplicationInfoFlags.of(0))
                        .loadLabel(pm)
                        .toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    installer
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /**
     * Looks the installed [packageName] up in the f-droid.org index (downloaded if the cached one
     * is out of date). F-Droid only counts as its source when it serves a build signed with the
     * key of the installed copy: F-Droid signs many apps itself, and such a build can't update a
     * copy from the developer (nor the other way round). Blocking.
     */
    fun lookUp(context: Context, packageName: String): SourceLookup {
        SourceRegistry.init(context)
        return try {
            val listing =
                FdroidSource.lookup(packageName, DeviceSpec.of(context))
                    ?: return SourceLookup.NotFound
            val sameKey =
                listing.signers.isEmpty() ||
                    CertVerifier.installedMatches(context, packageName, listing.signers) == true
            if (sameKey) SourceLookup.Found("fdroid:$packageName") else SourceLookup.OtherSigner
        } catch (e: IOException) {
            SourceLookup.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // IchthysOS: an index that isn't the expected shape fails the lookup, not the app.
            SourceLookup.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * What the user typed as a source, as a catalog `source` string; null when it is none:
     * - a GitHub or GitLab project address, or `owner/repo` (GitHub): its newest release's APK;
     * - any other https address: that file, checked again on every pass;
     * - `<scheme>:<spec>` as in catalog.list (`fdroid:<package>@<repo>`, `apkpure:<package>`, …).
     */
    fun parseSource(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val scheme = text.substringBefore(':', "")
        if (scheme in SourceRegistry.schemes && text.length > scheme.length + 1) return text
        GITHUB_URL.matchEntire(text)?.let {
            return "github:${it.groupValues[1]}/${it.groupValues[2]}"
        }
        GITLAB_URL.matchEntire(text)?.let {
            return "gitlab:${it.groupValues[1].removeSuffix(".git")}"
        }
        if (REPO.matches(text)) return "github:$text"
        if (text.startsWith("https://")) return "url:$text"
        return null
    }
}
