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

/**
 * `url:<direct-url>`: one APK at a fixed URL. The release fingerprint comes from the response
 * headers (ETag, else Last-Modified + length), so an unchanged file is not downloaded again; when
 * the server sends none, the APK is downloaded and its versionCode compared.
 */
object DirectUrlSource : Source {
    override val scheme = "url"

    override fun resolve(spec: String, device: DeviceSpec): Release {
        val url = spec.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw IOException("url: not an http(s) URL")
        }
        val head =
            try {
                Http.head(url)
            } catch (e: IOException) {
                null // Some servers dislike the probe; the download decides.
            }
        val fingerprint =
            head?.let { h ->
                h.etag?.let { "etag:$it" } ?: h.lastModified?.let { "lm:$it:${h.contentLength}" }
            }
        val name = head?.fileName ?: url.substringBefore('?').substringAfterLast('/')
        return Release(
            versionName = null,
            versionCode = null,
            fingerprint = fingerprint,
            files =
                listOf(
                    RemoteFile(
                        url = url,
                        name = Http.safeName(name),
                        size = head?.contentLength?.takeIf { it >= 0 },
                    )
                ),
        )
    }
}
