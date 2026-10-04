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

package org.ichthys.apps.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.pm.parsing.result.ParseTypeImpl
import android.util.apk.ApkSignatureVerifier
import java.io.File
import java.security.MessageDigest
import org.ichthys.apps.catalog.CatalogApp

/** A download is not what was pinned for the app: wrong package, unsigned, or another signer. */
class CertMismatchException(message: String) : SecurityException(message)

/**
 * The certificate pins: every APK of a session must be signed by one of the [CatalogApp.certs]
 * SHA-256 digests, or nothing is installed. For a catalog app those are the developer's
 * certificates, shipped with the ROM; for an adopted app, the ones its installed copy had when it
 * was adopted ([installedSigners], trust on first use). A key rotation (APK Signature Scheme v3
 * lineage) is accepted when the lineage includes a pinned certificate, the same rule
 * PackageManager applies to updates; with several signers, every signer must be pinned.
 */
object CertVerifier {
    /** SigningDetails.SignatureSchemeVersion.JAR: accept any scheme (v1 and up). */
    private const val MIN_SCHEME_JAR = 1

    /** Identity of a verified base APK. */
    data class BaseInfo(val packageName: String, val versionCode: Long, val versionName: String?)

    /**
     * Verifies [apks] (base first) against [app]'s pins. The base is read with
     * PackageManager.getPackageArchiveInfo(GET_SIGNING_CERTIFICATES); splits, which that cannot
     * parse, with the platform's ApkSignatureVerifier (full verification).
     */
    fun verify(context: Context, apks: List<File>, app: CatalogApp): BaseInfo {
        require(apks.isNotEmpty())
        val base = apks.first()
        val info =
            context.packageManager.getPackageArchiveInfo(
                base.path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            ) ?: throw CertMismatchException("${base.name} is not a valid APK")
        if (info.packageName != app.packageName) {
            throw CertMismatchException(
                "download is ${info.packageName}, expected ${app.packageName}"
            )
        }
        val signing = info.signingInfo ?: throw CertMismatchException("${base.name} is unsigned")
        check(base, current = currentSigners(signing), lineage = lineage(signing), app = app)
        for (split in apks.drop(1)) {
            val result =
                ApkSignatureVerifier.verify(
                    ParseTypeImpl.forParsingWithoutPlatformCompat().reset(),
                    split.path,
                    MIN_SCHEME_JAR,
                )
            if (result.isError) {
                throw CertMismatchException("${split.name}: ${result.errorMessage}")
            }
            val details = result.result
            val current = details.signatures?.toList().orEmpty()
            val past = details.pastSigningCertificates?.toList().orEmpty()
            check(split, current = current, lineage = past + current, app = app)
        }
        return BaseInfo(info.packageName, info.longVersionCode, info.versionName)
    }

    /**
     * Whether the installed [packageName] is signed by one of [pins]; null when not installed.
     * Updates are only attempted over a pinned install (anything else would fail anyway).
     */
    fun installedMatches(context: Context, packageName: String, pins: Set<String>): Boolean? {
        val info =
            try {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    ),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
        val signing = info.signingInfo ?: return false
        return matches(currentSigners(signing), lineage(signing), pins)
    }

    /**
     * The SHA-256 of the certificate(s) the installed [packageName] is signed with now (the
     * current key after a rotation); null when it is not installed. What an adoption pins.
     */
    fun installedSigners(context: Context, packageName: String): Set<String>? {
        val info =
            try {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    ),
                )
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
        return info.signingInfo?.let { currentSigners(it).map(::sha256).toSet() } ?: emptySet()
    }

    fun sha256(sig: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    /** The signer(s) of the APK contents (the current key after a rotation). */
    private fun currentSigners(s: SigningInfo): List<Signature> =
        s.apkContentsSigners?.toList().orEmpty()

    /** Current and past certificates; null history means several signers (no lineage). */
    private fun lineage(s: SigningInfo): List<Signature> =
        s.signingCertificateHistory?.toList() ?: currentSigners(s)

    private fun matches(current: List<Signature>, lineage: List<Signature>, pins: Set<String>) =
        when {
            current.isEmpty() -> false
            current.size > 1 -> current.all { sha256(it) in pins }
            else -> lineage.any { sha256(it) in pins }
        }

    private fun check(
        apk: File,
        current: List<Signature>,
        lineage: List<Signature>,
        app: CatalogApp,
    ) {
        if (!matches(current, lineage, app.certs)) {
            val got = current.joinToString { sha256(it).take(16) + "…" }
            throw CertMismatchException(
                "${apk.name} is signed by [$got], not by ${app.name}'s pinned certificate"
            )
        }
    }
}
