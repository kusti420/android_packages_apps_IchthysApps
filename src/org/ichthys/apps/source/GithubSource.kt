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

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `github:<owner>/<repo>[#<regex>[#<entry-regex>]]`: the newest non-prerelease release
 * (api.github.com/repos/<owner>/<repo>/releases/latest) and its first asset whose name matches the
 * regex (default `\.apk$`). When several match, the one built for this phone's ABI wins (arm64-v8a
 * / arm64 …), then a universal one, before assets for other ABIs.
 *
 * A repository that releases several products (Mullvad: the desktop app and the Android app) may
 * have a "latest" release without a matching asset; then the newest of its recent releases that
 * has one is taken.
 *
 * With an entry regex the asset is a zip (BCR ships a flashable module) and the APK is the first
 * entry of it whose path matches.
 *
 * A pre-release newer than that release, with a matching asset, comes along as [Release.beta]:
 * looked for among the [BETA_RELEASES] newest releases, with a second request (the release list
 * is large, so it is never asked for more than that). A pass sparing mobile data doesn't look.
 *
 * The API allows 60 requests an hour to callers without an account, counted per address, and a
 * phone often shares its address with many others. When the API refuses for that (403 or 429), the
 * release is read from the website instead: github.com/<owner>/<repo>/releases/latest redirects to
 * the latest release's tag, and its assets are listed at …/releases/expanded_assets/<tag>.
 */
object GithubSource : Source {
    override val scheme = "github"

    private const val DEFAULT_ASSET = "\\.apk$"

    /** How many recent releases are looked through when the latest one has no matching asset. */
    private const val RECENT_RELEASES = 30

    /** How many of the newest releases are looked through for a pre-release. */
    private const val BETA_RELEASES = 5
    private val ABI_WORDS =
        mapOf(
            "arm64-v8a" to listOf("arm64-v8a", "arm64_v8a", "arm64", "aarch64", "armv8"),
            "armeabi-v7a" to listOf("armeabi-v7a", "armeabi_v7a", "armv7", "arm32", "armeabi"),
            "x86_64" to listOf("x86_64", "x64", "amd64"),
            "x86" to listOf("x86", "i686", "i386"),
        )

    /** `href="…"`: the asset links of the website's asset list. */
    private val HREF = Regex("href\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /**
     * @property fingerprint identifies this very file: the API's asset id and upload time, or (from
     *   the website, which has neither) its download address
     * @property size in bytes; null when unknown
     */
    private data class Asset(
        val name: String,
        val url: String,
        val fingerprint: String,
        val size: Long?,
    )

    /** A release: its tag and the assets that match the spec (never empty). */
    private data class Found(val tag: String, val assets: List<Asset>)

    private data class Spec(val repo: String, val asset: Regex, val entry: Regex?)

    private fun parse(spec: String): Spec {
        val parts = spec.split('#')
        val repo = parts[0].trim().trim('/')
        if (!repo.matches(Regex("[\\w.-]+/[\\w.-]+"))) throw IOException("github: bad repo $repo")
        try {
            return Spec(
                repo = repo,
                asset = Regex(parts.getOrNull(1).orEmpty().ifEmpty { DEFAULT_ASSET }),
                entry = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Regex(it) },
            )
        } catch (e: PatternSyntaxException) {
            throw IOException("github: bad regex", e)
        }
    }

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val s = parse(spec)
        var api = true
        val found =
            try {
                fromApi(s)
            } catch (e: HttpException) {
                if (e.code != 403 && e.code != 429) throw e
                api = false
                fromWeb(s)
            }
        // The website lists no pre-releases cheaply; a failed look is no reason to fail the update.
        var betaChecked = api && !device.saveData
        val beta =
            if (!betaChecked) null
            else
                try {
                    prerelease(s, found.tag)
                } catch (e: IOException) {
                    betaChecked = false
                    null
                }
        return release(found, device)
            .copy(beta = beta?.let { release(it, device) }, betaChecked = betaChecked)
    }

    private fun release(found: Found, device: DeviceSpec): Release {
        val asset = found.assets.minBy { abiRank(it.name, device) }
        return Release(
            versionName = versionOf(found.tag),
            versionCode = null,
            fingerprint = asset.fingerprint,
            files =
                listOf(
                    RemoteFile(url = asset.url, name = Http.safeName(asset.name), size = asset.size)
                ),
        )
    }

    /**
     * The newest pre-release with a matching asset that is newer than the release [stableTag], or
     * null. The API lists releases newest first, so that is one listed before it.
     */
    private fun prerelease(s: Spec, stableTag: String): Found? {
        for (release in releases(s, BETA_RELEASES)) {
            if (release.optString("tag_name") == stableTag) return null
            if (release.optBoolean("draft") || !release.optBoolean("prerelease")) continue
            matching(release, s.asset)?.let {
                return it
            }
        }
        return null
    }

    /** The [count] newest releases, from the API. */
    private fun releases(s: Spec, count: Int): List<JSONObject> {
        val url = "https://api.github.com/repos/${s.repo}/releases?per_page=$count"
        val releases =
            try {
                JSONArray(Http.text(url, accept = "application/vnd.github+json"))
            } catch (e: JSONException) {
                throw IOException("bad JSON from $url: ${e.message}", e)
            }
        return (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
    }

    private fun noMatch(s: Spec) =
        IOException("github: no asset of ${s.repo} matches ${s.asset.pattern}")

    /** The latest release, else the newest recent one with a matching asset, from the API. */
    private fun fromApi(s: Spec): Found {
        val latest =
            Http.json(
                "https://api.github.com/repos/${s.repo}/releases/latest",
                accept = "application/vnd.github+json",
            )
        return matching(latest, s.asset) ?: recent(s) ?: throw noMatch(s)
    }

    /** The latest release from the website (see the class comment). */
    private fun fromWeb(s: Spec): Found {
        val base = "https://github.com/${s.repo}/releases"
        // Not read: only where the redirect leads matters.
        val latest = Http.head("$base/latest").url
        val tag =
            try {
                URI(latest).path.substringAfter("/releases/tag/", "")
            } catch (e: URISyntaxException) {
                ""
            }
        if (tag.isEmpty()) throw IOException("github: ${s.repo} has no latest release")
        // Tags may hold "/" (android/2026.12) and "+".
        val encoded = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
        val html = Http.text("$base/expanded_assets/$encoded", accept = "text/html")
        val assets =
            HREF.findAll(html)
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("/${s.repo}/releases/download/", ignoreCase = true) }
                .distinct()
                .mapNotNull { href ->
                    val name =
                        try {
                            URI(href).path.substringAfterLast('/')
                        } catch (e: URISyntaxException) {
                            return@mapNotNull null
                        }
                    Asset(name, "https://github.com$href", "web:$href", null)
                }
                .filter { s.asset.containsMatchIn(it.name) }
                .toList()
        if (assets.isEmpty()) throw noMatch(s)
        return Found(tag, assets)
    }

    override fun unpack(
        spec: String,
        files: List<File>,
        workDir: File,
        device: DeviceSpec,
    ): List<File> {
        val entry = parse(spec).entry ?: return files
        val bundle = files.single()
        ZipFile(bundle).use { zip ->
            val name =
                zip.entries().asSequence().map { it.name }.firstOrNull { entry.containsMatchIn(it) }
                    ?: throw IOException("github: no entry of ${bundle.name} matches ${entry.pattern}")
            val size = zip.getEntry(name).size.coerceAtLeast(0L)
            Http.requireFreeSpace(workDir, size)
            val apk = Zips.extract(zip, name, File(workDir, "base.apk"), Http.MAX_DOWNLOAD_BYTES)
            bundle.delete()
            return listOf(apk)
        }
    }

    /** [release] with its assets whose names match, or null when none does. */
    private fun matching(release: JSONObject, pattern: Regex): Found? {
        val assets = release.optJSONArray("assets") ?: return null
        return (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .map {
                Asset(
                    name = it.optString("name"),
                    url = it.optString("browser_download_url"),
                    fingerprint = "${it.optLong("id")}:${it.optString("updated_at")}",
                    size = it.optLong("size", -1).takeIf { size -> size > 0 },
                )
            }
            .filter { it.url.isNotEmpty() && pattern.containsMatchIn(it.name) }
            .ifEmpty { null }
            ?.let { Found(release.optString("tag_name"), it) }
    }

    /** The newest recent release (no draft, no prerelease) with a matching asset. */
    private fun recent(s: Spec): Found? {
        // The API lists them newest first.
        for (release in releases(s, RECENT_RELEASES)) {
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            matching(release, s.asset)?.let {
                return it
            }
        }
        return null
    }

    /**
     * The version a tag names, for comparing with the installed versionName: "v1.2.3" and
     * "android/2026.12" are 1.2.3 and 2026.12.
     */
    private fun versionOf(tag: String): String? =
        tag.substringAfterLast('/').removePrefix("v").removePrefix("V").ifEmpty { null }

    /** 0..n for this phone's ABIs in preference order, then universal, then other ABIs. */
    private fun abiRank(name: String, device: DeviceSpec): Int {
        val lower = name.lowercase()
        fun mentions(abi: String) =
            ABI_WORDS[abi].orEmpty().any { w ->
                Regex("(^|[^a-z0-9])" + Regex.escape(w) + "($|[^a-z0-9])").containsMatchIn(lower)
            }
        device.abis.forEachIndexed { i, abi -> if (mentions(abi)) return i }
        val other = ABI_WORDS.keys.any { mentions(it) }
        return if (other) 1000 else 100
    }
}
