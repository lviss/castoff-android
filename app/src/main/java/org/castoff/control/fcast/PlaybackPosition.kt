package org.castoff.control.fcast

/**
 * Anchor point for locally interpolating displayed playback position between
 * daemon-pushed `PlaybackUpdate` frames (state changes, plus roughly once a
 * second while playing), so the UI can advance the progress bar continuously
 * instead of snapping once a second. Reset on every new update, which both
 * corrects drift and handles pause/seek/stop naturally.
 *
 * [anchorElapsedRealtimeNanos] is a monotonic clock reading (`System.nanoTime()`),
 * not wall-clock time, so interpolation isn't disturbed by clock adjustments.
 */
data class PlaybackAnchor(
    val reportedTimeSeconds: Double,
    val anchorElapsedRealtimeNanos: Long,
    val speed: Double,
)

/**
 * Computes the displayed position at [nowElapsedRealtimeNanos] (a
 * `System.nanoTime()` reading) by advancing [anchor]'s reported time by
 * elapsed time * speed, clamped to `[0, durationSeconds]` (or just `[0, ...)`
 * when duration isn't known yet).
 */
fun interpolatePosition(
    anchor: PlaybackAnchor,
    nowElapsedRealtimeNanos: Long,
    durationSeconds: Double?,
): Double {
    val elapsedSeconds = (nowElapsedRealtimeNanos - anchor.anchorElapsedRealtimeNanos) / 1_000_000_000.0
    val position = anchor.reportedTimeSeconds + elapsedSeconds * anchor.speed
    val upperBound = durationSeconds ?: Double.MAX_VALUE
    return position.coerceIn(0.0, upperBound)
}
