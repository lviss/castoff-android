package org.castoff.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection line is the only thing on the screen that tells the user
 * whether the app is talking to the daemon, so its wording and its
 * connected/connecting/not-connected distinction are pinned here: the captain
 * must be able to glance at it and know, and a failure must say why.
 */
class ConnectionStatusTest {

    @Test
    fun `connected names the host and offers no retry`() {
        val status = ConnectionStatus.connected("10.0.2.2", 46899)

        assertEquals(ConnectionPhase.CONNECTED, status.phase)
        assertEquals("Connected to 10.0.2.2:46899", status.headline)
        assertNull(status.detail)
        assertFalse(status.canConnect)
    }

    @Test
    fun `connecting is a distinct, momentary state`() {
        val status = ConnectionStatus.connecting("10.0.2.2", 46899)

        assertEquals(ConnectionPhase.CONNECTING, status.phase)
        assertEquals("Connecting to 10.0.2.2:46899…", status.headline)
        assertNull(status.detail)
        assertFalse(status.canConnect)
    }

    @Test
    fun `connecting before the saved host is read does not name an empty endpoint`() {
        val status = ConnectionStatus.connecting("", 0)

        assertEquals("Connecting…", status.headline)
    }

    @Test
    fun `not connected states the failure reason and offers a manual connect`() {
        val status = ConnectionStatus.notConnected("10.0.2.2", 46899, "connection refused")

        assertEquals(ConnectionPhase.NOT_CONNECTED, status.phase)
        assertEquals("Not connected to 10.0.2.2:46899", status.headline)
        assertEquals("connection refused", status.detail)
        assertTrue(status.canConnect)
    }

    @Test
    fun `not configured points at the settings that are missing`() {
        val status = ConnectionStatus.notConfigured

        assertEquals(ConnectionPhase.NOT_CONFIGURED, status.phase)
        assertEquals("No TV box set", status.headline)
        assertTrue(status.detail!!.contains("Save"))
        assertFalse(status.canConnect)
    }
}
