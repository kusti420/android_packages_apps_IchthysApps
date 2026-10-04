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

import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.regex.PatternSyntaxException

/**
 * `gitlab:<group>/<project>[#<regex>]`: the newest release of a gitlab.com project
 * (gitlab.com/api/v4/projects/<path>/releases/permalink/latest) and the first file of it whose name
 * matches the regex (default `\.apk$`). A release's files are its asset links and the files
 * attached to its notes: projects like Aurora Store upload the APK into the release description
 * (`[AuroraStore-4.8.4.apk](/uploads/<hash>/AuroraStore-4.8.4.apk)`) instead of linking an asset.
 *
 * Only https links on gitlab.com are followed for attachments; an asset link may point anywhere,
 * over https. The download link (it carries the upload's hash) is the release fingerprint.
 */
object GitlabSource : Source {
    override val scheme = "gitlab"

    private const val HOST = "https://gitlab.com"
    private const val DEFAULT_FILE = "\\.apk$"

    /** `href="…"` in the rendered release notes, where upload links are already resolved. */
    private val HREF = Regex("href\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val project = spec.substringBefore('#').trim().trim('/')
        if (!project.matches(Regex("[\\w.-]+(/[\\w.-]+)+"))) {
            throw IOException("gitlab: bad project $project")
        }
        val pattern =
            try {
                Regex(spec.substringAfter('#', DEFAULT_FILE).ifEmpty { DEFAULT_FILE })
            } catch (e: PatternSyntaxException) {
                throw IOException("gitlab: bad file regex", e)
            }
        val id = URLEncoder.encode(project, "UTF-8")
        val release =
            Http.json(
                "$HOST/api/v4/projects/$id/releases/permalink/latest?include_html_description=true"
            )
        // name -> url, asset links first.
        val files = LinkedHashMap<String, String>()
        release.optJSONObject("assets")?.optJSONArray("links")?.let { links ->
            for (i in 0 until links.length()) {
                val link = links.optJSONObject(i) ?: continue
                val url = link.optString("direct_asset_url").ifEmpty { link.optString("url") }
                val name = link.optString("name").ifEmpty { url.substringAfterLast('/') }
                if (url.startsWith("https://")) files.putIfAbsent(name, url)
            }
        }
        for (m in HREF.findAll(release.optString("description_html"))) {
            val url =
                try {
                    URL(URL("$HOST/"), m.groupValues[1].replace("&amp;", "&"))
                } catch (e: MalformedURLException) {
                    continue
                }
            if (url.protocol != "https" || url.host != "gitlab.com") continue
            if (!url.path.contains("/uploads/")) continue
            files.putIfAbsent(url.path.substringAfterLast('/'), url.toString())
        }
        val (name, url) =
            files.entries.firstOrNull { pattern.containsMatchIn(it.key) }?.toPair()
                ?: throw IOException("gitlab: no file of $project matches ${pattern.pattern}")
        val tag = release.optString("tag_name")
        return Release(
            versionName = tag.removePrefix("v").ifEmpty { null },
            versionCode = null,
            fingerprint = url,
            files = listOf(RemoteFile(url = url, name = Http.safeName(name), httpsOnly = true)),
        )
    }
}
