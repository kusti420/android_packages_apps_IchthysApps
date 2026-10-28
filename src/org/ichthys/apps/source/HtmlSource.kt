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
import java.util.regex.PatternSyntaxException

/**
 * `html:<page-url>#<regex>[#<regex>…]`: fetches the page and takes its first link (`<a href>`)
 * whose address or text matches the regex as the download link. With several regexes, each one but
 * the last picks the link to the next page instead: celsoazevedo.com lists a release by its name
 * (`<a href="…/f/dl19/">LMC8.8_Release_2</a>`), and only the page behind that link has the files.
 *
 * Relative links resolve against their page, and the page a link was found on is sent as Referer,
 * which hosts like celsoazevedo.com require. Redirects are followed, but only over https: a link or
 * a redirect to cleartext http is refused. The download link itself (it carries the release number)
 * is the release fingerprint. A `#` inside a regex is written `\x23`.
 */
object HtmlSource : Source {
    override val scheme = "html"

    /** Pages on the way to the download link, the first one included. */
    private const val MAX_PAGES = 4

    /** `<a … href="…">text</a>`: the href (quoted either way, or bare) and the inner HTML. */
    private val ANCHOR =
        Regex(
            "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val MARKUP = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val parts = spec.split('#')
        val steps =
            try {
                parts.drop(1).filter { it.isNotEmpty() }.map { Regex(it) }
            } catch (e: PatternSyntaxException) {
                throw IOException("html: bad regex", e)
            }
        var page = parts.first().trim()
        if (page.isEmpty() || steps.isEmpty() || steps.size > MAX_PAGES) {
            throw IOException("html: need <url>#<regex>[#<regex>…]")
        }
        var referer: String? = null
        for (regex in steps.dropLast(1)) {
            val next = firstLink(page, referer, regex)
            referer = page
            page = next
        }
        val link = firstLink(page, referer, steps.last())
        // IchthysOS: a fixed link ("…/latest.apk") is the same link for every release: what the
        // server says about the file tells a new one apart (and its size, for the mobile-data
        // limit). Without either, the link alone, as before.
        val head =
            try {
                Http.head(link, page)
            } catch (e: IOException) {
                null
            }
        val stamp = head?.etag?.let { "etag:$it" } ?: head?.lastModified?.let { "lm:$it" }
        return Release(
            versionName = null,
            versionCode = null,
            fingerprint = if (stamp != null) "$link|$stamp" else link,
            files =
                listOf(
                    RemoteFile(
                        url = link,
                        name = Http.safeName(link),
                        referer = page,
                        httpsOnly = true,
                        size = head?.contentLength?.takeIf { it >= 0 },
                    )
                ),
        )
    }

    /** The first link on [page] whose address (made absolute) or text matches [regex]. */
    private fun firstLink(page: String, referer: String?, regex: Regex): String {
        val html = Http.text(page, referer, accept = "text/html,*/*", httpsOnly = true)
        val base = URL(page)
        for (a in ANCHOR.findAll(html)) {
            // Exactly one of the three href groups took part in the match.
            val href = unescape(a.groupValues[1] + a.groupValues[2] + a.groupValues[3]).trim()
            if (href.isEmpty() || href.startsWith("#")) continue
            val url =
                try {
                    URL(base, href)
                } catch (e: MalformedURLException) {
                    continue
                }
            val text = SPACE.replace(unescape(MARKUP.replace(a.groupValues[4], " ")), " ").trim()
            if (!regex.containsMatchIn(url.toString()) && !regex.containsMatchIn(text)) continue
            if (url.protocol != "https") throw IOException("html: refusing non-https link $url")
            return url.toString()
        }
        throw IOException("html: no link matches $regex on $page")
    }

    private fun unescape(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
