package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelVerificationCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun checksumIdentitySurvivesRestartAndRejectsChanges() {
        val directory = folder.newFolder()
        val file = folder.newFile().apply { writeText("original") }
        val sha = "a".repeat(64)
        ModelVerificationCache(directory).recordChecksum(file, sha, "source")
        val restored = ModelVerificationCache(directory)
        assertTrue(restored.checksumMatches(file, sha, "source"))
        assertFalse(restored.checksumMatches(file, "b".repeat(64), "source"))
        assertFalse(restored.checksumMatches(file, sha, "different-source"))
        val other = folder.newFile().apply { writeText("original"); setLastModified(file.lastModified()) }
        assertFalse(restored.checksumMatches(other, sha, "source"))
        val previousTime = file.lastModified()
        file.writeText("replaced") // identical size
        assertTrue(file.setLastModified(previousTime + 2000))
        assertFalse(restored.checksumMatches(file, sha, "source"))
    }

    @Test fun bundleCacheRequiresExactRevisionAndDetectorVersion() {
        val directory = folder.newFolder()
        val bundle = ModelBundle("id", "model", emptyList(), capabilities = ModelCapabilities(thinking = ThinkingCapability.ModelControlled))
        ModelVerificationCache(directory).recordBundle("7:artifact-a", bundle)
        val restored = ModelVerificationCache(directory)
        assertEquals(bundle, restored.readBundle("7:artifact-a"))
        assertNull(restored.readBundle("7:artifact-b"))
        assertNull(restored.readBundle("8:artifact-a"))
    }

    @Test fun legacyFileAndRestoredBundleShareTheirVerificationIdentity() {
        val file = folder.newFile().apply { writeText("model") }
        val artifact = ModelArtifact(ArtifactRole.Main, file.name, ArtifactSource.AppManaged(file.absolutePath), file.length(), file.lastModified())
        val bundle = ModelBundle("id", "model", listOf(artifact))
        val cache = ModelVerificationCache(folder.newFolder())
        val beforeRestoration = ModelVerificationCache.fileIdentity(file)
        cache.recordBundle(beforeRestoration, bundle)
        val afterRestoration = ModelVerificationCache.fileIdentity(File(artifact.source.location), artifact.role)
        assertEquals(bundle, cache.readBundle(afterRestoration))
    }
}
