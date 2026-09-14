package org.castoff.control.fcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state-transition tests for [playbackDisplayFor], the single rule every
 * playback-state update site in `MainActivity` goes through: daemon pushes,
 * the optimistic play/pause and stop toggles, and the host switch/clear reset.
 *
 * The behavior that matters to the UI: while playing the result carries a fresh
 * anchor so the ticker interpolates; every other transition clears the anchor
 * (so a stale one can never keep advancing the bar) but keeps the last known
 * position frozen and visible.
 */
class PlaybackDisplayTest {

    @Test
    fun `playing report creates an anchor and takes position and duration from the report`() {
        val display = playbackDisplayFor(
            state = PlaybackState.PLAYING,
            current = PlaybackDisplay.EMPTY,
            report = PlaybackReport(timeSeconds = 10.0, durationSeconds = 100.0, speed = 2.0),
            nowElapsedRealtimeNanos = 1_000L,
        )

        assertTrue(display.isPlaying)
        assertEquals(10.0f, display.positionSeconds!!, 0.0001f)
        assertEquals(100.0f, display.durationSeconds!!, 0.0001f)

        val anchor = display.anchor!!
        assertEquals(10.0, anchor.reportedTimeSeconds, 0.0001)
        assertEquals(2.0, anchor.speed, 0.0001)
        assertEquals(1_000L, anchor.anchorElapsedRealtimeNanos)
    }

    @Test
    fun `a playing report's anchor interpolates the displayed position forward`() {
        val display = playbackDisplayFor(
            state = PlaybackState.PLAYING,
            report = PlaybackReport(timeSeconds = 10.0, durationSeconds = 100.0, speed = 2.0),
            nowElapsedRealtimeNanos = 0L,
        )

        val advanced = interpolatePosition(
            anchor = display.anchor!!,
            nowElapsedRealtimeNanos = 1_000_000_000L,
            durationSeconds = display.durationSeconds?.toDouble(),
        )

        assertEquals(12.0, advanced, 0.0001)
    }

    @Test
    fun `a paused report clears the anchor but freezes the reported position`() {
        val playing = PlaybackDisplay(anchor = null, isPlaying = true, positionSeconds = 42f, durationSeconds = 90f)

        val display = playbackDisplayFor(
            state = PlaybackState.PAUSED,
            current = playing,
            report = PlaybackReport(timeSeconds = 40.0, durationSeconds = 90.0, speed = 1.0),
        )

        assertFalse(display.isPlaying)
        assertNull(display.anchor)
        assertEquals(40.0f, display.positionSeconds!!, 0.0001f)
        assertEquals(90.0f, display.durationSeconds!!, 0.0001f)
    }

    @Test
    fun `an optimistic pause with no report keeps the last position frozen and clears the anchor`() {
        val playing = PlaybackDisplay(anchor = null, isPlaying = true, positionSeconds = 42f, durationSeconds = 90f)

        val display = playbackDisplayFor(state = PlaybackState.PAUSED, current = playing)

        assertFalse(display.isPlaying)
        assertNull(display.anchor)
        assertEquals(42.0f, display.positionSeconds!!, 0.0001f)
        assertEquals(90.0f, display.durationSeconds!!, 0.0001f)
    }

    @Test
    fun `an optimistic resume does not anchor off stale data`() {
        val paused = PlaybackDisplay(anchor = null, isPlaying = false, positionSeconds = 42f, durationSeconds = 90f)

        val display = playbackDisplayFor(state = PlaybackState.PLAYING, current = paused)

        assertTrue(display.isPlaying)
        assertNull(display.anchor)
        assertEquals(42.0f, display.positionSeconds!!, 0.0001f)
        assertEquals(90.0f, display.durationSeconds!!, 0.0001f)
    }

    @Test
    fun `clearing or switching the host empties the whole display`() {
        val playing = PlaybackDisplay(anchor = null, isPlaying = true, positionSeconds = 42f, durationSeconds = 90f)

        val display = playbackDisplayFor(state = null, current = playing)

        assertFalse(display.isPlaying)
        assertNull(display.anchor)
        assertNull(display.positionSeconds)
        assertNull(display.durationSeconds)
    }

    @Test
    fun `a not-playing report without a time keeps the frozen position`() {
        val playing = PlaybackDisplay(anchor = null, isPlaying = true, positionSeconds = 42f, durationSeconds = 90f)

        val display = playbackDisplayFor(
            state = PlaybackState.PAUSED,
            current = playing,
            report = PlaybackReport(timeSeconds = null, durationSeconds = null, speed = null),
        )

        assertFalse(display.isPlaying)
        assertNull(display.anchor)
        assertEquals(42.0f, display.positionSeconds!!, 0.0001f)
        assertEquals(90.0f, display.durationSeconds!!, 0.0001f)
    }
}
