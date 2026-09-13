package org.castoff.control.fcast

/**
 * FCast protocol v2 opcodes (docs.fcast.org/protocol/v2), matching the subset
 * implemented by the castoff daemon (daemon/src/fcast.rs in lviss/castoff).
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
    PONG(13);

    companion object {
        fun fromInt(value: Int): Opcode? = entries.find { it.value == value }
    }
}
