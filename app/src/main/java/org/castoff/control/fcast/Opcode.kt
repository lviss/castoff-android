package org.castoff.control.fcast

/**
 * FCast protocol v2 opcodes (docs.fcast.org/protocol/v2), matching the subset
 * implemented by the castoff daemon (daemon/src/fcast.rs in lviss/castoff).
 *
 * Opcodes 14-19 are castoff's own private extension for the play queue (no
 * FCast v2 equivalent), beyond the protocol's reserved 0-13 range -- see the
 * daemon's README "Queueing (private extension)" section and
 * `daemon/src/fcast.rs`.
 */
enum class Opcode(val value: Int) {
    NONE(0),
    PLAY(1),
    PAUSE(2),
    RESUME(3),
    STOP(4),
    SEEK(5),
    PLAYBACK_UPDATE(6),
    VOLUME_UPDATE(7),
    SET_VOLUME(8),
    PLAYBACK_ERROR(9),
    SET_SPEED(10),
    VERSION(11),
    PING(12),
    PONG(13),
    REQUEST_QUEUE(14),
    QUEUE_STATE(15),
    QUEUE_JUMP_FORWARD(16),
    QUEUE_JUMP_BACKWARD(17),
    CLEAR_QUEUE(18),
    QUEUE_JUMP_TO_INDEX(19);

    companion object {
        fun fromInt(value: Int): Opcode? = entries.find { it.value == value }
    }
}
