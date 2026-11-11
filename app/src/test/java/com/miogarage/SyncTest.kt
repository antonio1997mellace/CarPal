package com.miogarage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    @Test
    fun decidesWhichSideWins() {
        fun act(local: String, remote: String?, sl: String?, sr: String?, empty: Boolean = false) = decide(local, remote, sl, sr, empty)
        assertEquals(SyncAction.UPLOAD, act("a", null, null, null)) // first sync of an account without data
        assertEquals(SyncAction.NONE, act("a", null, null, null, empty = true))
        assertEquals(SyncAction.NONE, act("a", "a", null, null))
        assertEquals(SyncAction.UPLOAD, act("b", "r", "a", "r")) // only the phone changed
        assertEquals(SyncAction.DOWNLOAD, act("a", "s", "a", "r")) // only the account changed
        assertEquals(SyncAction.DOWNLOAD, act("a", "r", null, null, empty = true)) // new phone
        assertEquals(SyncAction.ASK, act("a", "r", null, null)) // a phone with data joins an account with data
        assertEquals(SyncAction.ASK, act("b", "s", "a", "r")) // both changed
        assertEquals(SyncAction.UPLOAD, act("b", "r", "a", "r", empty = true)) // emptying the garage is a change too
    }

    @Test
    fun onlyDocumentPathsBecomeLocalFiles() {
        assertTrue(isDocPath("docs/12/Libretto/libretto.pdf"))
        assertTrue(isDocPath("docs/3/Varie/scontrino gomme.jpg"))
        assertFalse(isDocPath("garage.json"))
        assertFalse(isDocPath("docs/12/../../databases/garage"))
        assertFalse(isDocPath("docs/12/Libretto/.."))
        assertFalse(isDocPath("docs/12/Altro/file.pdf"))
        assertFalse(isDocPath("docs/12/.tmp"))
        assertFalse(isDocPath("docs/x/Varie/file.pdf"))
    }

    @Test
    fun fingerprintIgnoresOrder() = assertEquals(fingerprint(mapOf("a" to "1", "b" to "2")), fingerprint(mapOf("b" to "2", "a" to "1")))
}
