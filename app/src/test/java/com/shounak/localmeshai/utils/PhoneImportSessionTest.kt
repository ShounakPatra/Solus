package com.shounak.localmeshai.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneImportSessionTest {
    @Test fun successfulManagedCopyReleasesGrantsNotUsedByTheRegisteredBundle() = runBlocking {
        val released = mutableListOf<String>()
        val session = PhoneImportSession(releasePermission = { released.add(it) }, isUriRegistered = { false })
        session.recordGrant("content://provider/copied-task")
        session.onRegistrationSuccess(emptySet())
        assertEquals(listOf("content://provider/copied-task"), released)
        assertTrue(session.getSessionGrants().isEmpty())
    }


    @Test
    fun testSameFileReselectionPreservesReleaseOwnership() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val registeredUris = mutableSetOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { it in registeredUris }
        )

        // User inspects file A: newly acquired grant A
        session.recordGrant("content://media/external/file/A")
        session.replaceDraft(setOf("content://media/external/file/A"))

        assertEquals(setOf("content://media/external/file/A"), session.getSessionGrants())
        assertTrue("No URI should be released on initial draft", releasedUris.isEmpty())

        // User reselects file A along with new file B
        session.recordGrant("content://media/external/file/B")
        session.replaceDraft(setOf("content://media/external/file/A", "content://media/external/file/B"))

        // Both grants should still be owned by session; none released
        assertEquals(
            setOf("content://media/external/file/A", "content://media/external/file/B"),
            session.getSessionGrants()
        )
        assertTrue("Reselecting file A must not release it", releasedUris.isEmpty())

        // Now user replaces draft with only file C
        session.recordGrant("content://media/external/file/C")
        session.replaceDraft(setOf("content://media/external/file/C"))

        // A and B should now be released, C retained
        assertEquals(listOf("content://media/external/file/A", "content://media/external/file/B"), releasedUris)
        assertEquals(setOf("content://media/external/file/C"), session.getSessionGrants())

        // User dismisses without registering: C is released
        session.requestDismiss()
        assertEquals(
            listOf(
                "content://media/external/file/A",
                "content://media/external/file/B",
                "content://media/external/file/C"
            ),
            releasedUris
        )
        assertTrue(session.getSessionGrants().isEmpty())
    }

    @Test
    fun testCancellationDuringInspectionReleasesOnlyUnreferencedGrants() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        // Established draft with file A
        session.recordGrant("content://file/A")
        session.replaceDraft(setOf("content://file/A"))

        // New inspection starts: acquires file B, but gets cancelled before replacing draft
        session.recordGrant("content://file/B")
        session.onInspectionCancelledOrFailed()

        // B should be released; A preserved in session and active draft
        assertEquals(listOf("content://file/B"), releasedUris)
        assertEquals(setOf("content://file/A"), session.getSessionGrants())
        assertEquals(setOf("content://file/A"), session.getCurrentDraftUris())
    }

    @Test
    fun testDismissalDuringDelayedSuccessfulOrFailedStoreWrite() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        session.recordGrant("content://file/A")
        session.replaceDraft(setOf("content://file/A"))

        // Registration starts and reserves draft
        val reserved = session.reserveDraftForRegistration()
        assertNotNull(reserved)
        assertEquals(setOf("content://file/A"), reserved)
        assertTrue(session.isRegistering)

        // User dismisses sheet during active store write
        val immediatelyDismissed = session.requestDismiss()
        assertFalse("Dismissal must be deferred while store write is in progress", immediatelyDismissed)
        assertTrue(session.isDismissPending)
        assertTrue("Grants must not be released while persistence is in flight", releasedUris.isEmpty())

        // Delayed store write completes successfully -> ownership transferred, no release
        session.onRegistrationSuccess(setOf("content://file/A"))
        assertFalse(session.isRegistering)
        assertFalse(session.isDismissPending)
        assertTrue("Permissions must not be released on successful persistence", releasedUris.isEmpty())
        assertTrue(session.getSessionGrants().isEmpty())

        // Scenario 2: Test delayed failure path with a new session
        val releasedOnFailure = mutableListOf<String>()
        val session2 = PhoneImportSession(
            releasePermission = { releasedOnFailure.add(it) },
            isUriRegistered = { false }
        )
        session2.recordGrant("content://file/X")
        session2.replaceDraft(setOf("content://file/X"))
        assertNotNull(session2.reserveDraftForRegistration())
        assertFalse(session2.requestDismiss())
        assertTrue(session2.isDismissPending)

        // Delayed store write fails -> releases unreferenced session grants
        session2.onRegistrationFailure()
        assertFalse(session2.isRegistering)
        assertFalse(session2.isDismissPending)
        assertEquals(listOf("content://file/X"), releasedOnFailure)
        assertTrue(session2.getSessionGrants().isEmpty())
    }

    @Test
    fun testInspectionPausedImmediatelyBeforeGrantAcquisitionFollowedByDisposal() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        // Inspection starts
        assertTrue(session.startInspection())
        assertTrue(session.isInspecting)

        // Paused immediately before grant acquisition; during the pause, session is disposed (e.g. ViewModel cleared)
        session.dispose()
        assertTrue(session.isDisposed)

        // Now the delayed grant acquisition arrives
        session.recordGrant("content://file/late_arrival")

        // Because session was disposed, late-arriving grant must be immediately released, NOT retained
        assertEquals(listOf("content://file/late_arrival"), releasedUris)
        assertTrue("Disposed session must not retain grants", session.getSessionGrants().isEmpty())
    }

    @Test
    fun testCancellationWhileSessionMutexIsOccupied() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        // Occupy the session mutex
        session.mutex.lock()
        assertTrue(session.mutex.isLocked)

        // Launch a coroutine that waits on the mutex
        val blockedJob = launch(Dispatchers.Default) {
            session.recordGrant("content://file/blocked")
        }

        delay(50) // Ensure job is waiting on mutex
        blockedJob.cancelAndJoin() // Cancel while waiting

        // Unlock the mutex
        session.mutex.unlock()
        assertFalse(session.mutex.isLocked)

        // Mutex must remain fully functional and uncorrupted
        session.recordGrant("content://file/after_cancel")
        assertEquals(setOf("content://file/after_cancel"), session.getSessionGrants())
        session.dispose()
        assertEquals(listOf("content://file/after_cancel"), releasedUris)
    }

    @Test
    fun testRegisterPressedWhileReplacementInspectionIsActiveIsRejected() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        // Established draft
        session.recordGrant("content://file/initial")
        session.replaceDraft(setOf("content://file/initial"))

        // Replacement inspection is actively running
        assertTrue(session.startInspection())
        assertTrue(session.isInspecting)

        // User attempts to press Register while inspection is active
        val reserved = session.reserveDraftForRegistration()
        assertNull("Register draft reservation must be rejected while inspection is active", reserved)
        assertFalse("startRegistration must also return false while inspection is active", session.startRegistration())
        assertFalse("Must not be in registering state", session.isRegistering)

        // Finish inspection with replacement draft
        session.finishInspection(setOf("content://file/initial", "content://file/replacement"))
        assertFalse(session.isInspecting)

        // Registration can now succeed
        val reservedAfter = session.reserveDraftForRegistration()
        assertNotNull("Registration must be allowed after inspection completes", reservedAfter)
        assertTrue(session.isRegistering)

        // Conversely, starting an inspection while registration is active must be rejected
        assertFalse("Inspection must be rejected while registration is active", session.startInspection())
    }

    @Test
    fun testDuplicateRegistrationIgnored() = runBlocking {
        val session = PhoneImportSession(
            releasePermission = {},
            isUriRegistered = { false }
        )

        session.recordGrant("content://file/model")
        session.replaceDraft(setOf("content://file/model"))

        val firstReserve = session.reserveDraftForRegistration()
        assertNotNull(firstReserve)
        assertTrue(session.isRegistering)

        // Duplicate registration attempt while first is in-flight
        val secondReserve = session.reserveDraftForRegistration()
        assertNull("Duplicate registration draft reservation must be ignored", secondReserve)
        assertFalse("Duplicate startRegistration must return false", session.startRegistration())
    }

    @Test
    fun testTeardownRejectsNewOperations() = runBlocking {
        val session = PhoneImportSession(
            releasePermission = {},
            isUriRegistered = { false }
        )

        session.recordGrant("content://file/draft")
        session.replaceDraft(setOf("content://file/draft"))

        session.startTeardown()
        assertTrue(session.isTeardownStarted)

        // Operations rejected after teardown started
        assertFalse("startInspection must be rejected after teardown started", session.startInspection())
        assertFalse("startRegistration must be rejected after teardown started", session.startRegistration())
        assertNull("reserveDraftForRegistration must be rejected after teardown started", session.reserveDraftForRegistration())
    }

    @Test
    fun testDisposalWhileRegistrationActiveDefersCleanupUntilSaveCompletes() = runBlocking {
        val releasedUris = mutableListOf<String>()
        val session = PhoneImportSession(
            releasePermission = { releasedUris.add(it) },
            isUriRegistered = { false }
        )

        session.recordGrant("content://file/saved_model")
        session.replaceDraft(setOf("content://file/saved_model"))

        val reserved = session.reserveDraftForRegistration()
        assertNotNull(reserved)
        assertTrue(session.isRegistering)

        // ViewModel clears or sheet is disposed while save is in-flight
        session.dispose()
        assertTrue(session.isDisposed)
        assertTrue("Grants must not be released while registration is active even if disposed", releasedUris.isEmpty())

        // Save finishes successfully: ownership transferred to model store, no grants released
        session.onRegistrationSuccess(setOf("content://file/saved_model"))
        assertTrue("Permissions must be retained for registered model after delayed disposal", releasedUris.isEmpty())
    }

    @Test
    fun testOverlappingInspectionRejectedWhenAlreadyInspecting() = runBlocking {
        val session = PhoneImportSession(
            releasePermission = {},
            isUriRegistered = { false }
        )
        assertTrue(session.startInspection())
        assertTrue(session.isInspecting)

        // Second concurrent inspection must be rejected
        assertFalse("Second startInspection must be rejected when isInspecting is true", session.startInspection())

        session.onInspectionCancelledOrFailed()
        assertFalse(session.isInspecting)
        assertTrue("Inspection can start after previous finishes", session.startInspection())
    }
}
