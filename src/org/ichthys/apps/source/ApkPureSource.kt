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

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONException
import org.json.JSONObject

/**
 * `apkpure:<package>`: APKPure's latest XAPK (d.apkpure.com/b/XAPK/<package>?version=latest), a zip
 * of the base APK, its config splits and a manifest.json. Only the splits this phone needs are
 * installed (ported from IchthysPrebuilts/fetch.sh): the most preferred supported ABI, the closest
 * density at or above the screen's (else the highest offered, since bundles that ship density
 * splits refuse to install without one) and the user's languages (else English). Feature splits are
 * always kept. Some apps come as a plain APK from the same URL; that is installed as is.
 *
 * The version is read from the file name in Content-Disposition (e.g. "Curve_3.40.0_APKPure.xapk")
 * without downloading the body.
 *
 * Unpacking is bounded like the download: no APK over [MAX_ENTRY_BYTES], no more than
 * [MAX_TOTAL_BYTES] altogether (counted while writing, whatever the zip claims), and room for it
 * checked first.
 */
object ApkPureSource : Source {
    private const val TAG = "IchthysApps"
    override val scheme = "apkpure"

    private val DENSITIES =
        linkedMapOf(
            "ldpi" to 120,
            "mdpi" to 160,
            "tvdpi" to 213,
            "hdpi" to 240,
            "xhdpi" to 320,
            "xxhdpi" to 480,
            "xxxhdpi" to 640,
        )
    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips")
    private val LANGUAGE = Regex("[a-z]{2,3}")
    private val VERSION_IN_NAME = Regex("_v?(\\d[^_]*)_apkpure", RegexOption.IGNORE_CASE)

    /** The largest APK taken out of a bundle. */
    private const val MAX_ENTRY_BYTES = Http.MAX_DOWNLOAD_BYTES

    /** The most a bundle may unpack to (base and the chosen splits together). */
    private const val MAX_TOTAL_BYTES = 2 * Http.MAX_DOWNLOAD_BYTES
    private const val MAX_MANIFEST_BYTES = 1 shl 20

    private fun url(pkg: String) = "https://d.apkpure.com/b/XAPK/$pkg?version=latest"

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val pkg = spec.trim()
        if (pkg.isEmpty()) throw IOException("apkpure: empty package")
        val head = Http.head(url(pkg))
        val name = head.fileName
        val versionName = name?.let { VERSION_IN_NAME.find(it)?.groupValues?.get(1) }
        val id = name ?: head.url.substringBefore('?')
        return Release(
            versionName = versionName,
            versionCode = null,
            fingerprint = "$id:${head.contentLength}",
            files =
                listOf(
                    RemoteFile(
                        url = url(pkg),
                        name = "$pkg.xapk",
                        size = head.contentLength.takeIf { it >= 0 },
                    )
                ),
        )
    }

    override fun unpack(
        spec: String,
        files: List<File>,
        workDir: File,
        device: DeviceSpec,
    ): List<File> {
        val bundle = files.single()
        ZipFile(bundle).use { zip ->
            // A plain APK served from the XAPK endpoint. It stays where it was downloaded, so an
            // interrupted install finds it again.
            if (zip.getEntry("AndroidManifest.xml") != null) return listOf(bundle)
            val apks = splitIds(zip)
            if (apks.isEmpty()) throw IOException("XAPK without APKs")
            val baseEntry =
                apks.entries.firstOrNull { it.value == "base" }?.key
                    ?: throw IOException("XAPK without a base APK")
            val config = HashMap<String, String>() // qualifier -> entry
            val features = ArrayList<String>()
            for ((entry, id) in apks) {
                when {
                    entry == baseEntry -> {}
                    id.startsWith("config.") -> config[id.removePrefix("config.")] = entry
                    else -> features += entry
                }
            }
            val chosen = selectConfigSplits(config.keys, device)
            Log.i(TAG, "XAPK splits: ${config.keys.sorted()} -> $chosen, features $features")

            val wanted = ArrayList<Pair<String, File>>() // entry -> destination
            wanted += baseEntry to File(workDir, "base.apk")
            for (entry in features) {
                wanted += entry to File(workDir, "split_" + Http.safeName(entry))
            }
            for (q in chosen) {
                wanted += config.getValue(q) to File(workDir, "split_config.$q.apk")
            }
            // By the sizes the zip declares, before anything is written.
            val declared =
                wanted.sumOf { (entry, _) -> (zip.getEntry(entry)?.size ?: 0L).coerceAtLeast(0L) }
            if (declared > MAX_TOTAL_BYTES) throw IOException(tooLarge("the XAPK", MAX_TOTAL_BYTES))
            Http.requireFreeSpace(workDir, declared)

            val out = ArrayList<File>()
            var left = MAX_TOTAL_BYTES
            for ((entry, dest) in wanted) {
                if (left <= 0) throw IOException(tooLarge("the XAPK", MAX_TOTAL_BYTES))
                out += Zips.extract(zip, entry, dest, minOf(MAX_ENTRY_BYTES, left))
                left -= dest.length()
            }
            bundle.delete()
            return out
        }
    }

    /**
     * APK entry → split id ("base", "config.arm64_v8a", or a feature name), from manifest.json's
     * split_apks when present, else from the file names.
     */
    private fun splitIds(zip: ZipFile): Map<String, String> {
        val rootApks =
            zip.entries()
                .asSequence()
                .map(ZipEntry::getName)
                .filter { !it.contains('/') && it.endsWith(".apk") }
                .toList()
        val result = LinkedHashMap<String, String>()
        zip.getEntry("manifest.json")?.let { entry ->
            try {
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
                if (bytes.size > MAX_MANIFEST_BYTES) throw JSONException("manifest.json too large")
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val splits = json.optJSONArray("split_apks")
                if (splits != null) {
                    for (i in 0 until splits.length()) {
                        val s = splits.getJSONObject(i)
                        val file = s.optString("file")
                        if (file in rootApks) result[file] = s.optString("id", file)
                    }
                }
                if (result.isNotEmpty() && "base" !in result.values) {
                    val pkg = json.optString("package_name")
                    rootApks.firstOrNull { it == "$pkg.apk" }?.let { result[it] = "base" }
                }
            } catch (e: JSONException) {
                Log.w(TAG, "XAPK manifest.json unreadable; using file names", e)
                result.clear()
            }
        }
        if (result.isNotEmpty() && "base" in result.values) return result
        result.clear()
        val nonConfig = rootApks.filterNot { it.startsWith("config.") }
        val base =
            nonConfig.firstOrNull { it == "base.apk" }
                ?: nonConfig.singleOrNull()
                ?: nonConfig.maxByOrNull { zip.getEntry(it).size }
        for (apk in rootApks) result[apk] = if (apk == base) "base" else apk.removeSuffix(".apk")
        return result
    }

    /** The config qualifiers to install, from the [available] ones. */
    internal fun selectConfigSplits(available: Set<String>, device: DeviceSpec): List<String> {
        val chosen = ArrayList<String>()
        // ABI: the most preferred one the bundle has a split for.
        device.abis
            .map { it.replace('-', '_') }
            .firstOrNull { it in available }
            ?.let { chosen += it }
        // Density: the smallest at or above the screen's, else the highest offered.
        val densities = available.filter { it in DENSITIES }.sortedBy { DENSITIES.getValue(it) }
        val density =
            densities.firstOrNull { DENSITIES.getValue(it) >= device.densityDpi }
                ?: densities.lastOrNull()
        if (density != null) chosen += density
        // Languages: the user's, else English.
        val langs =
            available.filter { it !in DENSITIES && it !in ABIS && LANGUAGE.matches(it) }.toSet()
        val wanted = device.languages.filter { it in langs }
        chosen += wanted.ifEmpty { if ("en" in langs) listOf("en") else emptyList() }
        return chosen
    }

    private fun tooLarge(what: String, limit: Long) =
        "$what unpacks to more than ${limit shr 20} MB"
}
