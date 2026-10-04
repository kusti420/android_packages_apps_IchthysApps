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

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONException
import org.json.JSONObject

/** A response other than 2xx or a redirect. */
class HttpException(val code: Int, message: String) : IOException(message)

/**
 * The little HTTP the downloaders need, on [HttpURLConnection]: timeouts, a plain browser-like
 * User-Agent (APKPure and some file hosts refuse obvious bots), redirects followed by hand (so the
 * Referer and the Range headers survive them, and a source can refuse hops to cleartext http) and
 * downloads streamed to a file.
 *
 * Downloads are bounded: no file over [MAX_DOWNLOAD_BYTES], room for it checked first, and a
 * deadline for the whole transfer on top of the per-read timeout. An interrupted download keeps its
 * .part file and continues from there (HTTP Range) the next time, where the server allows it.
 */
object Http {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 8
    private const val MAX_TEXT_BYTES = 16 shl 20

    /** The largest file a source may serve (an APK, an XAPK, an F-Droid index). */
    const val MAX_DOWNLOAD_BYTES = 600L shl 20

    /** Kept free on the volume on top of what is about to be written. */
    private const val FREE_SPACE_RESERVE_BYTES = 64L shl 20

    /** A page or an API answer must be complete by then, however slowly it drips. */
    private const val TEXT_DEADLINE_MS = 2 * 60_000L

    /** One attempt at one file; what it got is resumed by the next attempt. */
    private const val DOWNLOAD_DEADLINE_MS = 60 * 60_000L

    /** "bytes <first>-<last>/<total>". */
    private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-\\d+/(\\d+)", RegexOption.IGNORE_CASE)

    val userAgent: String = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE})"

    /** Response headers of a GET whose body was not read. */
    data class Head(
        val url: String,
        val contentLength: Long,
        val etag: String?,
        val lastModified: String?,
        /** The Content-Disposition filename, if any. */
        val fileName: String?,
    )

    /**
     * Opens [url] and follows redirects; the returned connection has a 2xx response and must be
     * disconnected by the caller.
     *
     * @param headers further request headers, sent again on every hop
     * @param httpsOnly refuse cleartext http, for [url] and for every redirect
     * @throws HttpException for any other response
     */
    fun open(
        url: String,
        referer: String? = null,
        accept: String? = null,
        ua: String? = null,
        headers: Map<String, String> = emptyMap(),
        httpsOnly: Boolean = false,
    ): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            when (current.protocol) {
                "https" -> {}
                "http" ->
                    if (httpsOnly) throw IOException("refusing cleartext http://${current.host}")
                else -> throw IOException("unsupported URL $current")
            }
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua ?: userAgent)
            if (referer != null) c.setRequestProperty("Referer", referer)
            if (accept != null) c.setRequestProperty("Accept", accept)
            for ((name, value) in headers) c.setRequestProperty(name, value)
            val code =
                try {
                    c.responseCode
                } catch (e: IOException) {
                    c.disconnect()
                    throw e
                }
            when (code) {
                in 200..299 -> return c
                301,
                302,
                303,
                307,
                308 -> {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrEmpty()) throw IOException("redirect without Location")
                    current = URL(current, location)
                }
                else -> {
                    c.disconnect()
                    throw HttpException(code, "HTTP $code for ${current.host}${current.path}")
                }
            }
        }
        throw IOException("too many redirects for $url")
    }

    fun text(
        url: String,
        referer: String? = null,
        accept: String? = null,
        httpsOnly: Boolean = false,
    ): String {
        val c = open(url, referer, accept, httpsOnly = httpsOnly)
        try {
            val deadline = deadlineIn(TEXT_DEADLINE_MS)
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT_BYTES) throw IOException("response too large: $url")
                    if (expired(deadline)) throw IOException("response too slow: $url")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            c.disconnect()
        }
    }

    fun json(url: String, accept: String = "application/json"): JSONObject =
        try {
            JSONObject(text(url, accept = accept))
        } catch (e: JSONException) {
            throw IOException("bad JSON from $url: ${e.message}", e)
        }

    /** GET [url] for its headers only (many CDNs answer HEAD with 403/405). */
    fun head(url: String, referer: String? = null, ua: String? = null): Head {
        val c = open(url, referer, ua = ua)
        try {
            return Head(
                url = c.url.toString(),
                contentLength = c.contentLengthLong,
                etag = c.getHeaderField("ETag"),
                lastModified = c.getHeaderField("Last-Modified"),
                fileName = dispositionFileName(c.getHeaderField("Content-Disposition")),
            )
        } finally {
            c.disconnect()
        }
    }

    /**
     * Streams [file] to [dest] (through a .part file), checking [RemoteFile.sha256] when given.
     * [onProgress] gets (bytes so far, total or -1). Cancellable between chunks.
     *
     * A .part file left by an interrupted attempt is continued with a Range request when the server
     * had said how long the file is: under If-Range with the ETag or Last-Modified it sent, else
     * only if the total length is still the same. In every other case the download starts over.
     */
    suspend fun download(file: RemoteFile, dest: File, onProgress: (Long, Long) -> Unit): File {
        val ctx = currentCoroutineContext()
        try {
            return downloadBlocking(file, dest, { !ctx.isActive }, onProgress)
        } catch (e: IOException) {
            ctx.ensureActive() // a cancellation surfaces as CancellationException
            throw e
        }
    }

    /** [download] for callers off a coroutine; stops early once [cancelled] returns true. */
    fun downloadBlocking(
        file: RemoteFile,
        dest: File,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val meta = File(dest.path + ".part.meta")
        val saved = readMeta(meta)?.takeIf { part.length() in 1 until it.total }
        val c =
            try {
                open(
                    file.url,
                    file.referer,
                    ua = file.userAgent,
                    headers = if (saved != null) rangeHeaders(part.length(), saved) else emptyMap(),
                    httpsOnly = file.httpsOnly,
                )
            } catch (e: HttpException) {
                if (saved == null || e.code != 416) throw e
                // Range not satisfiable: the file is no longer the one the part was started from.
                part.delete()
                meta.delete()
                return downloadBlocking(file, dest, cancelled, onProgress)
            }
        // Whether a failure leaves the part behind for the next attempt.
        var keepPart = false
        try {
            var offset = 0L
            var total = c.contentLengthLong
            if (saved != null && c.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                // The rest of the same file: it must continue exactly where the part ends.
                offset = part.length()
                total = saved.total
                if (contentRange(c) != (offset to total)) {
                    throw IOException("unexpected Content-Range for ${file.name}")
                }
            }
            if (total > MAX_DOWNLOAD_BYTES) throw IOException(tooLarge(file))
            keepPart = offset > 0
            requireFreeSpace(dest.parentFile, if (total >= 0) total - offset else 0)
            if (offset == 0L) {
                // A fresh body: note what it is, so that an interruption can be continued.
                if (total >= 0) writeMeta(meta, PartMeta(total, validatorOf(c))) else meta.delete()
                keepPart = total >= 0
            }
            var done = offset
            var lastReport = offset
            val deadline = deadlineIn(DOWNLOAD_DEADLINE_MS)
            c.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        if (expired(deadline)) {
                            throw IOException("download of ${file.name} took too long")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_DOWNLOAD_BYTES) {
                            keepPart = false
                            throw IOException(tooLarge(file))
                        }
                        out.write(buf, 0, n)
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    out.fd.sync()
                }
            }
            if (total > 0 && done != total) throw IOException("truncated download: $done/$total")
            onProgress(done, total)
            if (file.sha256 != null && !sha256(part).equals(file.sha256, ignoreCase = true)) {
                keepPart = false
                throw IOException("SHA-256 mismatch for ${file.name}")
            }
        } catch (e: Exception) {
            if (!keepPart) {
                part.delete()
                meta.delete()
            }
            throw e
        } finally {
            c.disconnect()
        }
        meta.delete()
        if (!part.renameTo(dest)) throw IOException("cannot move ${part.name}")
        return dest
    }

    /**
     * Throws unless the volume of [dir] has room for [bytes] more (0 when unknown) with a reserve
     * on top.
     */
    fun requireFreeSpace(dir: File?, bytes: Long) {
        val free = dir?.usableSpace ?: return
        val need = bytes.coerceAtLeast(0) + FREE_SPACE_RESERVE_BYTES
        if (free < need) {
            throw IOException(
                "not enough storage: ${need shr 20} MB needed, ${free shr 20} MB free"
            )
        }
    }

    /** What a .part file is the start of: the full length and the server's validator, if any. */
    private data class PartMeta(val total: Long, val validator: String?)

    private fun readMeta(meta: File): PartMeta? =
        try {
            val lines = meta.readLines()
            PartMeta(lines[0].toLong(), lines.getOrNull(1)?.ifEmpty { null })
        } catch (e: Exception) {
            null // missing or unreadable: the download starts over
        }

    private fun writeMeta(meta: File, m: PartMeta) =
        meta.writeText("${m.total}\n${m.validator.orEmpty()}")

    /** A strong ETag, else Last-Modified: what If-Range may carry. */
    private fun validatorOf(c: HttpURLConnection): String? =
        c.getHeaderField("ETag")?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            ?: c.getHeaderField("Last-Modified")?.takeIf { it.isNotEmpty() }

    private fun rangeHeaders(offset: Long, saved: PartMeta): Map<String, String> {
        // identity: the offsets must be those of the file itself, not of a gzip stream.
        val headers = mutableMapOf("Range" to "bytes=$offset-", "Accept-Encoding" to "identity")
        if (saved.validator != null) headers["If-Range"] = saved.validator
        return headers
    }

    /** (first byte, total length) from the Content-Range of a 206, or null. */
    private fun contentRange(c: HttpURLConnection): Pair<Long, Long>? {
        val m = CONTENT_RANGE.find(c.getHeaderField("Content-Range").orEmpty()) ?: return null
        val first = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[2].toLongOrNull() ?: return null
        return first to total
    }

    private fun tooLarge(file: RemoteFile) =
        "${file.name} is larger than ${MAX_DOWNLOAD_BYTES shr 20} MB"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deadlineIn(ms: Long): Long = System.nanoTime() + ms * 1_000_000

    private fun expired(deadline: Long): Boolean = System.nanoTime() - deadline >= 0

    /** A file name safe to use in the cache directory. */
    fun safeName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }

    private fun dispositionFileName(header: String?): String? {
        if (header == null) return null
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let {
            return try {
                URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
            } catch (e: IllegalArgumentException) {
                it.groupValues[1].trim()
            }
        }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }
}
