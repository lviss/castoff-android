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

/**
 * The fresh values carried by a daemon `PlaybackUpdate` frame, when a UI update
 * is being driven by one rather than by a local/optimistic command.
 */
data class PlaybackReport(
    val timeSeconds: Double?,
    val durationSeconds: Double?,
    val speed: Double?,
)

/** The playback values the control screen displays, plus the anchor driving interpolation. */
data class PlaybackDisplay(
    val anchor: PlaybackAnchor?,
    val isPlaying: Boolean,
    val positionSeconds: Float?,
    val durationSeconds: Float?,
) {
    companion object {
        /** No playback known: before any update, or after the host was cleared/switched. */
        val EMPTY = PlaybackDisplay(
            anchor = null,
            isPlaying = false,
            positionSeconds = null,
            durationSeconds = null,
        )
    }
}

/**
 * Single source of truth for mapping a playback [state] onto the values the
 * control screen displays, given the [current] display and, when the transition
 * came from a daemon push, its fresh [report].
 *
 * Shared rules (every caller -- daemon pushes and local optimistic toggles --
 * goes through here so they can't drift apart):
 * - [PlaybackDisplay.isPlaying] mirrors [state].
 * - [PlaybackDisplay.anchor] is non-null only while playing *and* a fresh
 *   [report] supplied something to anchor on. Every other transition clears it,
 *   so the interpolation ticker can never keep advancing off a stale anchor
 *   (the manual pause/stop and host-switch cases).
 * - when not playing, [current]'s last known position/duration are kept frozen
 *   and visible rather than cleared, unless a fresh [report] overrides them.
 *
 * A `null` [state] means no playback is known at all (the host was switched or
 * cleared) and produces [PlaybackDisplay.EMPTY].
 */
fun playbackDisplayFor(
    state: PlaybackState?,
    current: PlaybackDisplay = PlaybackDisplay.EMPTY,
    report: PlaybackReport? = null,
    nowElapsedRealtimeNanos: Long = System.nanoTime(),
): PlaybackDisplay {
    if (state == null) return PlaybackDisplay.EMPTY

    val playing = state == PlaybackState.PLAYING
    val positionSeconds = report?.timeSeconds?.toFloat() ?: current.positionSeconds
    val anchor = if (playing && report != null) {
        PlaybackAnchor(
            reportedTimeSeconds = report.timeSeconds ?: positionSeconds?.toDouble() ?: 0.0,
            anchorElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            speed = report.speed ?: 1.0,
        )
    } else {
        null
    }
    return PlaybackDisplay(
        anchor = anchor,
        isPlaying = playing,
        positionSeconds = positionSeconds,
        durationSeconds = report?.durationSeconds?.toFloat() ?: current.durationSeconds,
    )
}
