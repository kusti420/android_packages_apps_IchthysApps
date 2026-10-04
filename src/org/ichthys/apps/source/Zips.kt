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
import java.util.zip.ZipFile

/** Taking APKs out of the bundles some sources serve (an XAPK, a release zip), bounded. */
object Zips {
    /**
     * Writes [entry] of [zip] to [dest], giving up (and deleting it) once it passes [limit] bytes,
     * whatever size the zip declares.
     */
    fun extract(zip: ZipFile, entry: String, dest: File, limit: Long): File {
        val e = zip.getEntry(entry) ?: throw IOException("missing $entry")
        if (e.size > limit) throw IOException(tooLarge(entry, limit))
        var written = 0L
        zip.getInputStream(e).use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > limit) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (written > limit) {
            dest.delete()
            throw IOException(tooLarge(entry, limit))
        }
        return dest
    }

    private fun tooLarge(what: String, limit: Long) =
        "$what unpacks to more than ${limit shr 20} MB"
}
