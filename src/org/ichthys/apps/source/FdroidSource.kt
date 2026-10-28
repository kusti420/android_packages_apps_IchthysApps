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

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.jar.JarFile
import org.json.JSONObject

/**
 * `fdroid:<package>[@<repo-url>]`: an F-Droid repository's index-v2 (f-droid.org/repo by default).
 * entry.json names the current index and its SHA-256; the index is downloaded only when that
 * changes (cached per repo, shared by every app from it) and read as a stream, so the 60 MB
 * f-droid.org index (20 MB on the wire) never sits in memory. A background pass on a metered
 * network ([DeviceSpec.saveData]) makes do with an index of the last [STALE_OK_MS] instead of
 * downloading the day's new one. The newest stable version this phone can run (minSdkVersion,
 * nativecode) is chosen, and its APK is checked against the index's SHA-256 on top of the
 * catalog's certificate pin. A newer build in a release channel ("Beta") comes along as
 * [Release.beta].
 *
 * [lookup] answers whether a repo has a package at all and who signed it there, which is how an
 * app installed from elsewhere finds its update source.
 */
object FdroidSource : Source {
    override val scheme = "fdroid"

    private const val DEFAULT_REPO = "https://f-droid.org/repo"

    /** How old a cached index may be for a pass that spares mobile data. */
    private const val STALE_OK_MS = 3 * 24 * 60 * 60_000L

    /** Where indexes are cached; set by [SourceRegistry.init]. */
    @Volatile var indexDir: File? = null

    /**
     * IchthysOS: where the repos' signing keys first seen and their newest index timestamps are
     * kept (not the cache, which can be cleared); set by [SourceRegistry.init].
     */
    @Volatile var stateDir: File? = null

    /**
     * IchthysOS: the signing-certificate SHA-256 of repos whose key is known, as F-Droid
     * publishes it. Other repos: the "?fingerprint=" of the catalog entry, else the first key seen.
     */
    private val PINNED_REPOS =
        mapOf(DEFAULT_REPO to "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab")

    /** "https://repo?fingerprint=AB12…" -> the repo's URL and the fingerprint, if any. */
    private fun splitRepo(repo: String): Pair<String, String?> {
        val url = repo.substringBefore('?').trimEnd('/')
        val fp =
            repo.substringAfter("fingerprint=", "").substringBefore('&').trim().lowercase()
                .replace(":", "").ifEmpty { null }
        return url to fp
    }

    private data class Version(
        val name: String?,
        val code: Long,
        val file: String,
        val sha256: String?,
        val size: Long?,
        val minSdk: Int,
        val nativecode: List<String>,
        val stable: Boolean,
        /** SHA-256 of the signing certificate(s), lower-case hex; empty when the index has none. */
        val signers: List<String>,
    )

    /** What a repo has for a package: its newest stable build for this phone. */
    data class Listing(val versionName: String?, val signers: Set<String>)

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val pkg = spec.substringBefore('@').trim()
        val (repo, fingerprint) = splitRepo(spec.substringAfter('@', DEFAULT_REPO).trim())
        if (pkg.isEmpty()) throw IOException("fdroid: empty package")
        val versions = findVersions(index(repo, fingerprint, device.saveData), pkg)
        if (versions.isEmpty()) throw IOException("fdroid: $pkg not in $repo")
        val v = best(versions, device) ?: throw IOException("fdroid: no compatible $pkg build")
        val beta = best(versions, device, betas = true)?.takeIf { it.code > v.code }
        return release(repo, v).copy(beta = beta?.let { release(repo, it) })
    }

    private fun release(repo: String, v: Version) =
        Release(
            versionName = v.name,
            versionCode = v.code,
            fingerprint = v.sha256 ?: "${v.file}:${v.code}",
            files =
                listOf(
                    RemoteFile(
                        url = repo + "/" + v.file.trimStart('/'),
                        name = Http.safeName(v.file),
                        sha256 = v.sha256,
                        size = v.size,
                    )
                ),
        )

    /**
     * The newest stable build of [pkg] this phone can run in [repo] (f-droid.org by default), or
     * null when the repo doesn't have the package (or nothing this phone can run).
     */
    fun lookup(pkg: String, device: DeviceSpec, repo: String = DEFAULT_REPO): Listing? {
        val (url, fingerprint) = splitRepo(repo)
        val v = best(findVersions(index(url, fingerprint, device.saveData), pkg), device)
        return v?.let { Listing(it.name, it.signers.toSet()) }
    }

    /** The newest build this phone can run: stable only, or with [betas] any channel's. */
    private fun best(
        versions: List<Version>,
        device: DeviceSpec,
        betas: Boolean = false,
    ): Version? =
        versions
            .filter { (betas || it.stable) && it.minSdk <= device.sdk }
            .filter { it.nativecode.isEmpty() || it.nativecode.any { a -> a in device.abis } }
            // IchthysOS: the newest build the pinned key signed, not one by another key that
            // can never be installed (and would be fetched again every pass). An entry that
            // names no signer stays a candidate; CertVerifier checks what is downloaded.
            .filter {
                device.signers.isEmpty() || it.signers.isEmpty() ||
                    it.signers.any { s -> s in device.signers }
            }
            .maxByOrNull { it.code }

    /**
     * The repo's current index-v2, downloaded when entry.json points at a new one. With [saveData]
     * a cached index downloaded within [STALE_OK_MS] is used as it is, without asking.
     */
    private fun index(repo: String, fingerprint: String?, saveData: Boolean): File {
        val dir = indexDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "fdroid")
        dir.mkdirs()
        val key = sha256Hex(repo.toByteArray()).take(16)
        val file = File(dir, "$key.json")
        val stamp = File(dir, "$key.sha256")
        if (saveData && file.isFile && stamp.isFile) {
            // Negative when the clock was set back: then ask.
            val age = System.currentTimeMillis() - file.lastModified()
            if (age in 0 until STALE_OK_MS) return file
        }
        // IchthysOS: entry.json from the signed entry.jar, not the bare one: it names the index
        // and its hash, so a mirror or anyone on the path could otherwise serve any index.
        val entry = signedEntry(repo, fingerprint, dir, key)
        val idx = entry.optJSONObject("index") ?: throw IOException("fdroid: entry.json no index")
        val name = idx.optString("name", "/index-v2.json")
        val sha = idx.optString("sha256").lowercase().ifEmpty { null }
        synchronized(this) {
            if (sha != null && file.isFile && stamp.isFile && stamp.readText().trim() == sha) {
                return file
            }
            Http.downloadBlocking(
                RemoteFile(url = repo + "/" + name.trimStart('/'), name = file.name, sha256 = sha),
                file,
            )
            if (sha != null) stamp.writeText(sha) else stamp.delete()
            return file
        }
    }

    /**
     * IchthysOS: entry.json out of [repo]'s entry.jar, after checking its JAR signature is the
     * repo's key ([PINNED_REPOS], the catalog's [fingerprint], or the first key seen, which must
     * not change) and that it isn't older than an index already seen (a rollback).
     */
    private fun signedEntry(repo: String, fingerprint: String?, dir: File, key: String): JSONObject {
        val jar = File(dir, "$key.entry.jar")
        jar.delete()
        Http.downloadBlocking(RemoteFile(url = "$repo/entry.jar", name = jar.name), jar)
        val (text, signer) =
            try {
                JarFile(jar, true).use { jf ->
                    val e = jf.getJarEntry("entry.json")
                        ?: throw IOException("fdroid: $repo entry.jar has no entry.json")
                    // Read to the end: that is when JarFile checks the signature.
                    val bytes = jf.getInputStream(e).use { it.readBytes() }
                    val cert = e.certificates?.firstOrNull()
                        ?: throw IOException("fdroid: $repo entry.json is not signed")
                    String(bytes, Charsets.UTF_8) to sha256Hex(cert.encoded)
                }
            } catch (e: SecurityException) {
                throw IOException("fdroid: $repo entry.jar signature is broken", e)
            } finally {
                jar.delete()
            }
        val state = (stateDir ?: dir).apply { mkdirs() }
        val seenKey = File(state, "$key.signer")
        val expected = PINNED_REPOS[repo] ?: fingerprint
            ?: seenKey.takeIf { it.isFile }?.readText()?.trim()
        if (expected == null) {
            seenKey.writeText(signer)
        } else if (expected != signer) {
            throw IOException("fdroid: $repo index is signed by an unexpected key")
        }
        val entry = JSONObject(text)
        val timestamp = entry.optLong("timestamp", 0L)
        val seenTime = File(state, "$key.timestamp")
        val newest = seenTime.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        if (timestamp < newest) {
            throw IOException("fdroid: $repo index is older than one already seen")
        }
        if (timestamp > newest) seenTime.writeText(timestamp.toString())
        return entry
    }

    /** Streams index-v2 to packages.<pkg>.versions. */
    private fun findVersions(index: File, pkg: String): List<Version> {
        JsonReader(index.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "packages") {
                    r.skipValue()
                    continue
                }
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != pkg) {
                        r.skipValue()
                        continue
                    }
                    return readPackage(r)
                }
                r.endObject()
            }
        }
        return emptyList()
    }

    private fun readPackage(r: JsonReader): List<Version> {
        val out = ArrayList<Version>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "versions") {
                r.skipValue()
                continue
            }
            r.beginObject()
            while (r.hasNext()) {
                r.nextName() // version hash
                readVersion(r)?.let { out += it }
            }
            r.endObject()
        }
        r.endObject()
        return out
    }

    private fun readVersion(r: JsonReader): Version? {
        var file: String? = null
        var sha: String? = null
        var size: Long? = null
        var signers = emptyList<String>()
        var name: String? = null
        var code = -1L
        var minSdk = 1
        var native = emptyList<String>()
        var stable = true
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "file" ->
                    r.obj { k ->
                        when (k) {
                            "name" -> file = r.nextString()
                            "sha256" -> sha = r.nextString().lowercase()
                            "size" -> size = r.nextLong()
                            else -> r.skipValue()
                        }
                    }
                "manifest" ->
                    r.obj { k ->
                        when (k) {
                            "versionName" -> name = r.nextString()
                            "versionCode" -> code = r.nextLong()
                            "usesSdk" ->
                                r.obj { s ->
                                    when (s) {
                                        "minSdkVersion" -> minSdk = r.nextInt()
                                        else -> r.skipValue()
                                    }
                                }
                            "nativecode" -> native = r.strings()
                            "signer" ->
                                r.obj { s ->
                                    when (s) {
                                        "sha256" -> signers = r.strings().map { it.lowercase() }
                                        else -> r.skipValue()
                                    }
                                }
                            else -> r.skipValue()
                        }
                    }
                // Any release channel (e.g. "Beta") means not a stable release.
                "releaseChannels" -> stable = r.strings().isEmpty()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val f = file ?: return null
        if (code < 0) return null
        return Version(name, code, f, sha, size, minSdk, native, stable, signers)
    }

    private inline fun JsonReader.obj(onKey: (String) -> Unit) {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return
        }
        beginObject()
        while (hasNext()) onKey(nextName())
        endObject()
    }

    private fun JsonReader.strings(): List<String> {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return emptyList()
        }
        val out = ArrayList<String>()
        beginArray()
        while (hasNext()) out += nextString()
        endArray()
        return out
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
