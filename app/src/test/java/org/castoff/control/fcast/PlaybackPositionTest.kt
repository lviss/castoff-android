package org.castoff.control.fcast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure math, no socket/coroutine timing involved: given an anchor and an
 * elapsed monotonic-clock delta, does `interpolatePosition` compute the
 * position a smoothly-updating progress bar should show.
 */
class PlaybackPositionTest {

    @Test
    fun `advances position by elapsed time times speed`() {
        val anchor = PlaybackAnchor(
            reportedTimeSeconds = 10.0,
            anchorElapsedRealtimeNanos = 0L,
            speed = 1.0,
        )

        val position = interpolatePosition(anchor, nowElapsedRealtimeNanos = 2_000_000_000L, durationSeconds = 100.0)

        assertEquals(12.0, position, 0.0001)
    }

    @Test
    fun `scales elapsed time by playback speed`() {
        val anchor = PlaybackAnchor(
            reportedTimeSeconds = 10.0,
            anchorElapsedRealtimeNanos = 0L,
            speed = 2.0,
        )

        val position = interpolatePosition(anchor, nowElapsedRealtimeNanos = 3_000_000_000L, durationSeconds = 100.0)

        assertEquals(16.0, position, 0.0001)
    }

    @Test
    fun `clamps to duration when interpolation overshoots the end`() {
        val anchor = PlaybackAnchor(
            reportedTimeSeconds = 95.0,
            anchorElapsedRealtimeNanos = 0L,
            speed = 1.0,
        )

        val position = interpolatePosition(anchor, nowElapsedRealtimeNanos = 10_000_000_000L, durationSeconds = 100.0)

        assertEquals(100.0, position, 0.0001)
    }

    @Test
    fun `clamps to zero for a negative elapsed delta`() {
        val anchor = PlaybackAnchor(
            reportedTimeSeconds = 5.0,
            anchorElapsedRealtimeNanos = 10_000_000_000L,
            speed = 1.0,
        )

        val position = interpolatePosition(anchor, nowElapsedRealtimeNanos = 0L, durationSeconds = 100.0)

        assertEquals(0.0, position, 0.0001)
    }

    @Test
    fun `does not clamp an upper bound when duration is unknown`() {
        val anchor = PlaybackAnchor(
            reportedTimeSeconds = 10.0,
            anchorElapsedRealtimeNanos = 0L,
            speed = 1.0,
        )

        val position = interpolatePosition(anchor, nowElapsedRealtimeNanos = 5_000_000_000L, durationSeconds = null)

        assertEquals(15.0, position, 0.0001)
    }
}
