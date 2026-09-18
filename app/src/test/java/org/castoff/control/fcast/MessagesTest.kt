package org.castoff.control.fcast

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies JSON field names/casing match the daemon's serde structs
 * (daemon/src/fcast.rs): plain camelCase field names, optional fields
 * omitted rather than sent as null wherever this client leaves them unset.
 */
class MessagesTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `play message serializes only the url field when others are unset`() {
        val encoded = json.encodeToString(PlayMessage(url = "https://example.com/video.mp4"))
        assertEquals("""{"url":"https://example.com/video.mp4"}""", encoded)
    }

    @Test
    fun `seek message serializes the time field`() {
        val encoded = json.encodeToString(SeekMessage(time = 42.5))
        assertEquals("""{"time":42.5}""", encoded)
    }

    @Test
    fun `set volume message serializes the volume field`() {
        val encoded = json.encodeToString(SetVolumeMessage(volume = 0.5))
        assertEquals("""{"volume":0.5}""", encoded)
    }

    @Test
    fun `playback update state deserializes from the daemon's numeric wire value`() {
        val decoded = Json.decodeFromString(
            PlaybackUpdateMessage.serializer(),
            """{"generationTime":1234,"state":1,"time":1.5,"duration":10.0,"speed":1.0}""",
        )
        assertEquals(PlaybackState.PLAYING, PlaybackState.fromInt(decoded.state))
    }
}
