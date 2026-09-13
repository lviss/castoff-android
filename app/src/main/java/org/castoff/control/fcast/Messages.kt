package org.castoff.control.fcast

import kotlinx.serialization.Serializable

/**
 * Sender -> receiver: start playback. Field set mirrors the daemon's
 * `PlayMessage` (daemon/src/fcast.rs) so a `Play` frame built here is
 * accepted as-is; `content`/`headers` are omitted here since this client
 * only ever sends a plain `url`.
 */
@Serializable
data class PlayMessage(
    val container: String? = null,
    val url: String? = null,
    val content: String? = null,
    val time: Double? = null,
    val volume: Double? = null,
    val speed: Double? = null,
)

@Serializable
data class SeekMessage(val time: Double)

@Serializable
data class SetVolumeMessage(val volume: Double)

@Serializable
data class SetSpeedMessage(val speed: Double)

@Serializable
data class VersionMessage(val version: Int)

enum class PlaybackState(val value: Int) {
    IDLE(0),
    PLAYING(1),
    PAUSED(2);

    companion object {
        fun fromInt(value: Int): PlaybackState? = entries.find { it.value == value }
    }
}

@Serializable
data class PlaybackUpdateMessage(
    val generationTime: Long,
    val state: Int,
    val time: Double? = null,
    val duration: Double? = null,
    val speed: Double? = null,
)

@Serializable
data class VolumeUpdateMessage(
    val generationTime: Long,
    val volume: Double,
)

@Serializable
data class PlaybackErrorMessage(val message: String)
