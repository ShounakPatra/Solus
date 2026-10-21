package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateArtifactVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val targetPackage = "com.shounak.localmeshai"
    private val installedVersionCode = 100L
    private val validCertFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val evilCertFingerprint = "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF"

    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder("update_test")
    }

    private fun createDummyApkFile(name: String): File {
        val file = File(testDir, name)
        file.writeBytes(ByteArray(512) { 0x50.toByte() })
        return file
    }

    @Test
    fun testValidApkVerificationSucceeds() {
        val apkFile = createDummyApkFile("valid.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 101L,
                    signatures = listOf(validCertFingerprint)
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should succeed", result is VerificationResult.Success)
        assertTrue("File must be retained", apkFile.exists())
    }

    @Test
    fun testPackageNameMismatchIsRejectedAndDeleted() {
        val apkFile = createDummyApkFile("wrong_package.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = "com.evil.malware",
                    versionCode = 105L,
                    signatures = listOf(validCertFingerprint)
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should fail on package mismatch", result is VerificationResult.Failure)
        assertFalse("Rejected APK file must be deleted immediately", apkFile.exists())
    }

    @Test
    fun testDowngradeVersionCodeIsRejectedAndDeleted() {
        val apkFile = createDummyApkFile("downgrade.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 99L, // Lower than installed 100L
                    signatures = listOf(validCertFingerprint)
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should fail on downgrade", result is VerificationResult.Failure)
        assertFalse("Rejected APK file must be deleted immediately", apkFile.exists())
    }

    @Test
    fun testSameVersionCodeIsRejectedAndDeleted() {
        val apkFile = createDummyApkFile("same_version.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 100L, // Equal to installed 100L
                    signatures = listOf(validCertFingerprint)
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should fail on same version", result is VerificationResult.Failure)
        assertFalse("Rejected APK file must be deleted immediately", apkFile.exists())
    }

    @Test
    fun testSignatureMismatchIsRejectedAndDeleted() {
        val apkFile = createDummyApkFile("wrong_signer.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 105L,
                    signatures = listOf(evilCertFingerprint) // Different certificate
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should fail on signature mismatch", result is VerificationResult.Failure)
        assertFalse("Rejected APK file must be deleted immediately", apkFile.exists())
    }

    @Test
    fun testCorruptArchiveIsRejectedAndDeleted() {
        val apkFile = createDummyApkFile("corrupt.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo? = null // Failed extraction
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue("Verification should fail on corrupt archive", result is VerificationResult.Failure)
        assertFalse("Corrupt APK file must be deleted immediately", apkFile.exists())
    }

    @Test
    fun testMissingOrEmptyFileIsRejected() {
        val emptyFile = File(testDir, "empty.apk")
        emptyFile.createNewFile()
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo? = null
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(emptyFile)
        assertTrue("Empty file must be rejected", result is VerificationResult.Failure)
    }

    @Test
    fun testInstalledSignaturesMissing_Rejected() {
        val apkFile = createDummyApkFile("installed_sig_missing.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 101L,
                    signatures = listOf(validCertFingerprint)
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = emptyList(), // Missing installed signatures
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue(result is VerificationResult.Failure)
        assertEquals("Installed application signing identity could not be determined", (result as VerificationResult.Failure).reason)
        assertFalse(apkFile.exists())
    }

    @Test
    fun testCandidateSignaturesMissing_Rejected() {
        val apkFile = createDummyApkFile("candidate_sig_missing.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 101L,
                    signatures = emptyList() // Missing candidate signatures
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue(result is VerificationResult.Failure)
        assertEquals("APK signing certificate identity could not be determined", (result as VerificationResult.Failure).reason)
        assertFalse(apkFile.exists())
    }

    @Test
    fun testSignerSetMismatch_Rejected() {
        val apkFile = createDummyApkFile("signer_set_mismatch.apk")
        val extraCertFingerprint = "99:88:77:66:55:44:33:22:11:00:AA:BB:CC:DD:EE:FF:99:88:77:66:55:44:33:22:11:00:AA:BB:CC:DD:EE:FF"
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                return ExtractedApkInfo(
                    packageName = targetPackage,
                    versionCode = 101L,
                    signatures = listOf(validCertFingerprint, extraCertFingerprint) // Superset, not exact match
                )
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue(result is VerificationResult.Failure)
        assertTrue((result as VerificationResult.Failure).reason.contains("Signature mismatch"))
        assertFalse(apkFile.exists())
    }

    @Test
    fun testExtractorException_ConvertedToFailure() {
        val apkFile = createDummyApkFile("corrupt_metadata.apk")
        val fakeExtractor = object : ApkMetadataExtractor {
            override fun extract(apkFile: File): ExtractedApkInfo {
                throw SecurityException("Corrupt manifest structure")
            }
        }

        val verifier = UpdateArtifactVerifier(
            expectedPackageName = targetPackage,
            currentVersionCode = installedVersionCode,
            currentSignatures = listOf(validCertFingerprint),
            extractor = fakeExtractor
        )

        val result = verifier.verify(apkFile)
        assertTrue(result is VerificationResult.Failure)
        assertTrue((result as VerificationResult.Failure).reason.contains("Failed to extract package metadata"))
        assertFalse(apkFile.exists())
    }
}
