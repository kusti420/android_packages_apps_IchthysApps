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

package org.ichthys.apps.catalog

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** What Ichthys Apps does with an app. */
enum class Kind {
    /**
     * A catalog app that is not in the image: offered on the Recommended screen, installed once
     * its id is in apps.install, kept updated, uninstalled when the id leaves the list.
     */
    APP,

    /**
     * A catalog app that is a system app of the image: never installed or removed here, only
     * updated over the built-in copy.
     */
    BUNDLED,

    /**
     * Not in the catalog: an app the user installed elsewhere (a store, a download) and told
     * Ichthys Apps to keep updated (apps.adopted). Only updated; its certificate pin is the one it
     * was signed with when it was adopted (trust on first use), see
     * [org.ichthys.apps.config.Adopted].
     */
    ADOPTED,
}

/**
 * One app Ichthys Apps handles: a catalog entry (packages/apps/IchthysPrebuilts/catalog.list), or
 * an adopted app made to look like one ([Kind.ADOPTED]; its id is its package name, which, unlike a
 * catalog id, has a dot).
 *
 * @property source "<scheme>:<spec>", resolved by the [org.ichthys.apps.source.SourceRegistry]
 * @property certs SHA-256 digests (lower-case hex) of the developer signing certificates; a
 *   download signed by none of them is refused
 * @property setup named post-install actions the system (IchthysPreinstallController) performs;
 *   Ichthys Apps ignores them
 */
data class CatalogApp(
    val id: String,
    val kind: Kind,
    val packageName: String,
    val name: String,
    val category: String,
    val default: Boolean,
    val source: String,
    val certs: Set<String>,
    val setup: List<String>,
    val description: String,
) {
    val scheme: String
        get() = source.substringBefore(':')

    val spec: String
        get() = source.substringAfter(':', "")
}

/**
 * The catalog shipped in the ROM, /product/etc/ichthys/apps-catalog.json (schema version 2;
 * version 1 had no "kind").
 */
class Catalog(val apps: List<CatalogApp>) {
    private val byId = apps.associateBy { it.id }
    private val byPackage = apps.associateBy { it.packageName }

    /** The apps offered on the Recommended screen: what apps.install may list. */
    val recommended: List<CatalogApp> = apps.filter { it.kind == Kind.APP }

    /** The image's system apps, kept updated. */
    val bundled: List<CatalogApp> = apps.filter { it.kind == Kind.BUNDLED }

    operator fun get(id: String): CatalogApp? = byId[id]

    fun forPackage(packageName: String): CatalogApp? = byPackage[packageName]

    /**
     * Ids pre-checked on the Recommended screen before apps.install was ever written (with the
     * catalog apps that are installed already). Nothing else: they are not a default for
     * apps.install.
     */
    val defaultIds: List<String>
        get() = recommended.filter { it.default }.map { it.id }

    companion object {
        private const val TAG = "IchthysApps"
        const val PATH = "/product/etc/ichthys/apps-catalog.json"
        private const val VERSION = 2

        @Volatile private var cached: Catalog? = null

        /** The catalog, parsed once per process (it only changes with an OTA). */
        fun get(): Catalog = cached ?: load(File(PATH)).also { cached = it }

        fun load(file: File): Catalog =
            try {
                parse(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "catalog $file unreadable", e)
                Catalog(emptyList())
            }

        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val version = root.optInt("version", 1)
            if (version > VERSION) {
                Log.w(TAG, "catalog version $version, newer than $VERSION; reading anyway")
            }
            val arr = root.optJSONArray("apps") ?: JSONArray()
            val apps = ArrayList<CatalogApp>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind =
                    when (val k = o.optString("kind", "app")) {
                        "app" -> Kind.APP
                        "bundled" -> Kind.BUNDLED
                        else -> {
                            // Never guess: an entry of a kind this build doesn't know is left alone.
                            Log.w(TAG, "catalog entry ${o.optString("id")} has unknown kind $k")
                            continue
                        }
                    }
                val app =
                    CatalogApp(
                        id = o.getString("id"),
                        kind = kind,
                        packageName = o.getString("package"),
                        name = o.optString("name", o.getString("id")),
                        category = o.optString("category", ""),
                        default = o.optBoolean("default", false),
                        source = o.getString("source"),
                        certs = o.optJSONArray("certs").strings().map { it.lowercase() }.toSet(),
                        setup = o.optJSONArray("setup").strings(),
                        description = o.optString("description", ""),
                    )
                if (app.certs.isEmpty()) {
                    // Never install anything without a pin.
                    Log.w(TAG, "catalog entry ${app.id} has no certs; skipped")
                    continue
                }
                apps += app
            }
            return Catalog(apps)
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else List(length()) { getString(it) }
    }
}
