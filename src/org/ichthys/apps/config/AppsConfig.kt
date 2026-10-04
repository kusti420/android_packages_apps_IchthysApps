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

package org.ichthys.apps.config

import android.content.Context
import android.net.Uri
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.internal.ichthys.IIchthysConfig
import org.ichthys.apps.catalog.Catalog
import org.ichthys.apps.catalog.Kind

/**
 * One entry of apps.adopted: an app installed from elsewhere that Ichthys Apps keeps updated.
 * Written `"<package>[@<sha256>[+<sha256>…]]=<source>"`, for example
 * `"org.example.app@3f31…b06e=fdroid:org.example.app"`.
 *
 * @property source "<scheme>:<spec>", like a catalog entry's
 * @property certs the pinned signing certificate(s), SHA-256 in lower-case hex: the ones the app
 *   was signed with when it was adopted (trust on first use). Empty in an entry written by hand
 *   without a pin; Ichthys Apps then pins the installed copy's certificate the first time it sees
 *   the entry and remembers that itself.
 */
data class Adopted(val packageName: String, val source: String, val certs: Set<String>) {
    /**
     * The entry as stored. In the source, "%", "," and line breaks are written %25, %2C and %0A,
     * so that an entry never contains the list separator of the stored setting.
     */
    fun format(): String {
        val pin = if (certs.isEmpty()) "" else "@" + certs.sorted().joinToString("+")
        val src = source.replace("%", "%25").replace(",", "%2C").replace("\n", "%0A")
        return "$packageName$pin=$src"
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val CERT = Regex("[0-9a-f]{64}")

        /** Null for an entry that is not `<package>[@<certs>]=<scheme>:<spec>`. */
        fun parse(entry: String): Adopted? {
            val head = entry.substringBefore('=', "").trim()
            val src =
                entry
                    .substringAfter('=', "")
                    .trim()
                    .replace("%0A", "\n")
                    .replace("%2C", ",")
                    .replace("%25", "%")
            val pkg = head.substringBefore('@')
            val certs =
                head.substringAfter('@', "").lowercase().split('+').filter { it.isNotEmpty() }
            if (!PACKAGE.matches(pkg) || certs.any { !CERT.matches(it) }) return null
            if (src.substringBefore(':', "").isEmpty() || src.substringAfter(':', "").isEmpty()) {
                return null
            }
            return Adopted(pkg, src, certs.toSet())
        }
    }
}

/**
 * The apps.* options of ichthys.nix as Ichthys Apps sees them.
 *
 * @property install apps.install as stored. The option has no default: null (key unset) means that
 *   no list was confirmed yet, never "the catalog's defaults"; an empty list ("" stored) is an
 *   explicit "none".
 * @property confirmed apps.confirmed: the user picked their apps (first-run screen) or ichthys.nix
 *   set it.
 * @property adopted apps.adopted: the apps installed from elsewhere that are kept updated. One
 *   entry per package (the first wins); entries that don't parse are dropped.
 */
data class AppsState(
    val install: List<String>?,
    val autoUpdate: Boolean,
    val wifiOnly: Boolean,
    val confirmed: Boolean,
    val adopted: List<Adopted> = emptyList(),
) {
    /**
     * Whether there is a list to act on: apps.confirmed and an explicit apps.install. Until then
     * nothing is installed or removed, and the first-run prompt stands.
     */
    val ready: Boolean
        get() = confirmed && install != null

    /**
     * The catalog ids Ichthys Apps keeps installed (none until [ready]). Only [Kind.APP] entries:
     * a bundled app is not something apps.install decides about.
     */
    fun managed(catalog: Catalog): List<String> =
        if (ready) install.orEmpty().filter { catalog[it]?.kind == Kind.APP } else emptyList()
}

/** A write through the ichthys_config binder failed (bad snippet, missing service or option). */
class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads apps.* from Settings.Secure (where IchthysConfigService's bindings store them) and writes
 * them only through the "ichthys_config" binder, so every change is a recorded ichthys.nix
 * generation that `cmd ichthys_config` and Theme Studio can show and roll back.
 */
object AppsConfig {
    private const val TAG = "IchthysApps"
    private const val SERVICE = "ichthys_config"
    /** Generation source shown in `cmd ichthys_config generations`. */
    private const val SOURCE = "Ichthys Apps"

    const val KEY_INSTALL = "ichthys_apps_install"
    const val KEY_AUTO_UPDATE = "ichthys_apps_auto_update"
    const val KEY_WIFI_ONLY = "ichthys_apps_wifi_only"
    const val KEY_CONFIRMED = "ichthys_apps_confirmed"
    const val KEY_ADOPTED = "ichthys_apps_adopted"
    val KEYS = listOf(KEY_INSTALL, KEY_AUTO_UPDATE, KEY_WIFI_ONLY, KEY_CONFIRMED, KEY_ADOPTED)

    fun uri(key: String): Uri = Settings.Secure.getUriFor(key)

    fun read(context: Context): AppsState {
        val cr = context.contentResolver
        fun bool(key: String, def: Boolean): Boolean =
            when (Settings.Secure.getString(cr, key)?.trim()?.lowercase()) {
                "1",
                "true" -> true
                "0",
                "false" -> false
                else -> def
            }
        val raw = Settings.Secure.getString(cr, KEY_INSTALL)
        return AppsState(
            install = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
            autoUpdate = bool(KEY_AUTO_UPDATE, true),
            wifiOnly = bool(KEY_WIFI_ONLY, false),
            confirmed = bool(KEY_CONFIRMED, false),
            adopted = parseAdopted(Settings.Secure.getString(cr, KEY_ADOPTED)),
        )
    }

    /**
     * apps.adopted as stored: its entries joined by the option's list separator. An entry never
     * contains a comma or a line break ([Adopted.format]), so either may be that separator.
     */
    private fun parseAdopted(raw: String?): List<Adopted> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, Adopted>()
        for (entry in raw.split(',', '\n')) {
            if (entry.isBlank()) continue
            val adopted = Adopted.parse(entry)
            if (adopted == null) {
                Log.w(TAG, "apps.adopted: ignoring \"$entry\"")
                continue
            }
            out.putIfAbsent(adopted.packageName, adopted)
        }
        return out.values.toList()
    }

    /**
     * Writes apps.install (and apps.confirmed when given), always as an explicit list: also when it
     * equals the catalog's defaults or is empty. Returns the new generation.
     */
    fun setInstall(ids: Collection<String>, confirmed: Boolean? = null): Int {
        val sb = StringBuilder("{ apps.install = ").append(nixList(ids)).append(";")
        if (confirmed != null) sb.append(" apps.confirmed = ").append(confirmed).append(";")
        return apply(sb.append(" }").toString())
    }

    /** Writes apps.adopted. Returns the new generation. */
    fun setAdopted(adopted: Collection<Adopted>): Int =
        apply("{ apps.adopted = ${nixList(adopted.map { it.format() })}; }")

    fun setAutoUpdate(on: Boolean): Int = apply("{ apps.autoUpdate = $on; }")

    fun setWifiOnly(on: Boolean): Int = apply("{ apps.wifiOnly = $on; }")

    /**
     * Merges [snippet] into the live configuration as a new generation:
     * `IIchthysConfig.apply(snippet, merge = true, "Ichthys Apps", myUserId)`.
     */
    fun apply(snippet: String): Int {
        val service =
            IIchthysConfig.Stub.asInterface(ServiceManager.getService(SERVICE))
                ?: throw ConfigException("$SERVICE service not running")
        try {
            val gen = service.apply(snippet, true, SOURCE, UserHandle.myUserId())
            Log.i(TAG, "generation $gen: $snippet")
            return gen
        } catch (e: IllegalArgumentException) {
            // "line:col: reason" from the parser, or an option this build doesn't know.
            throw ConfigException(e.message ?: "invalid config", e)
        } catch (e: SecurityException) {
            throw ConfigException(e.message ?: "permission denied", e)
        } catch (e: RemoteException) {
            throw ConfigException("$SERVICE died", e)
        }
    }

    /** `[ "a" "b" ]`, quoted like NixPrinter does. */
    fun nixList(ids: Collection<String>): String =
        if (ids.isEmpty()) "[ ]"
        else ids.joinToString(" ", prefix = "[ ", postfix = " ]") { nixString(it) }

    private fun nixString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                // A regex in an adopted app's source may hold "${"; the parser rejects that
                // unescaped (no interpolation).
                '$' -> sb.append("\\$")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
