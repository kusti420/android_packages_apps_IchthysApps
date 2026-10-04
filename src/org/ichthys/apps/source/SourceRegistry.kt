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
import java.io.File
import java.io.IOException

/** Catalog scheme → downloader. Keep in sync with IchthysPrebuilts/gen_catalog.py's SCHEMES. */
object SourceRegistry {
    private val sources: Map<String, Source> =
        listOf(
                ApkPureSource,
                GithubSource,
                GitlabSource,
                FdroidSource,
                HtmlSource,
                DirectUrlSource,
            )
            .associateBy { it.scheme }

    val schemes: Set<String>
        get() = sources.keys

    /** Gives the sources that cache between runs (F-Droid indexes) their directory. */
    fun init(context: Context) {
        FdroidSource.indexDir = File(context.cacheDir, "fdroid")
    }

    operator fun get(scheme: String): Source? = sources[scheme]

    /** The downloader for a catalog `source` string, or an [IOException] naming the scheme. */
    fun forSource(source: String): Source {
        val scheme = source.substringBefore(':')
        return sources[scheme] ?: throw IOException("unknown source scheme \"$scheme\"")
    }
}
