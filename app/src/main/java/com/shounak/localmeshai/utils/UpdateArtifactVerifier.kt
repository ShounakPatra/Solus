package com.shounak.localmeshai.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ExtractedApkInfo(
    val packageName: String,
    val versionCode: Long,
    val signatures: List<String>
)

sealed class VerificationResult {
    data class Success(val info: ExtractedApkInfo) : VerificationResult()
    data class Failure(val reason: String) : VerificationResult()
}

interface ApkMetadataExtractor {
    fun extract(apkFile: File): ExtractedApkInfo?
}

class AndroidApkMetadataExtractor(private val context: Context) : ApkMetadataExtractor {
    override fun extract(apkFile: File): ExtractedApkInfo? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        val packageName = pkgInfo.packageName.takeUnless { it.isBlank() } ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            val sigs = if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.apkContentsSigners?.takeIf { it.isNotEmpty() }
                        ?: signingInfo.signingCertificateHistory
                }
            } else null
            sigs?.map { UpdateArtifactVerifier.certificateFingerprint(it.toByteArray()) } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.map { UpdateArtifactVerifier.certificateFingerprint(it.toByteArray()) } ?: emptyList()
        }

        ExtractedApkInfo(
            packageName = packageName,
            versionCode = versionCode,
            signatures = signatures
        )
    }.getOrNull()
}

/**
 * Validates downloaded APK update artifacts independently of incoming intents.
 * Enforces strict package ID verification, monotonic version upgrade check,
 * and signature pinning against the currently installed application.
 */
class UpdateArtifactVerifier(
    val expectedPackageName: String,
    val currentVersionCode: Long,
    val currentSignatures: List<String>,
    private val extractor: ApkMetadataExtractor
) {
    constructor(context: Context) : this(
        expectedPackageName = context.packageName,
        currentVersionCode = getInstalledVersionCode(context),
        currentSignatures = getInstalledSignatures(context),
        extractor = AndroidApkMetadataExtractor(context)
    )

    fun verify(apkFile: File): VerificationResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return VerificationResult.Failure("APK file does not exist or is empty")
        }

        val info = try {
            extractor.extract(apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            return VerificationResult.Failure(
                "Failed to extract package metadata from APK file: ${e.message ?: e::class.java.simpleName}"
            )
        }

        if (info == null) {
            apkFile.delete()
            return VerificationResult.Failure("Failed to extract package metadata from APK file")
        }

        if (info.packageName != expectedPackageName) {
            apkFile.delete()
            return VerificationResult.Failure(
                "Package name mismatch: expected '$expectedPackageName' but found '${info.packageName}'"
            )
        }

        if (info.versionCode <= currentVersionCode) {
            apkFile.delete()
            return VerificationResult.Failure(
                "Version code downgrade or re-install rejected: APK versionCode ${info.versionCode} is not newer than installed $currentVersionCode"
            )
        }

        if (currentSignatures.isEmpty()) {
            apkFile.delete()
            return VerificationResult.Failure(
                "Installed application signing identity could not be determined"
            )
        }

        if (info.signatures.isEmpty()) {
            apkFile.delete()
            return VerificationResult.Failure(
                "APK signing certificate identity could not be determined"
            )
        }

        val currentSignersSet = currentSignatures.toSet()
        val apkSignersSet = info.signatures.toSet()
        if (currentSignersSet != apkSignersSet) {
            apkFile.delete()
            return VerificationResult.Failure(
                "Signature mismatch: APK signing certificate set does not match installed application"
            )
        }

        return VerificationResult.Success(info)
    }

    companion object {
        fun certificateFingerprint(signatureBytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatureBytes)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        fun getInstalledVersionCode(context: Context): Long {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        }

        fun getInstalledSignatures(context: Context): List<String> {
            val pm = context.packageManager
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = pkgInfo.signingInfo
                    val sigs = if (signingInfo != null) {
                        if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.apkContentsSigners?.takeIf { it.isNotEmpty() }
                                ?: signingInfo.signingCertificateHistory
                        }
                    } else null
                    sigs?.map { certificateFingerprint(it.toByteArray()) } ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    pkgInfo.signatures?.map { certificateFingerprint(it.toByteArray()) } ?: emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
