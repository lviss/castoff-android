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

    @Test
    fun `queue state message deserializes items and current index from the daemon's shape`() {
        val decoded = Json.decodeFromString(
            QueueStateMessage.serializer(),
            """{"generationTime":1234,"items":[{"url":"https://a"},{"url":"https://b","container":"video/mp4"}],"currentIndex":1}""",
        )
        assertEquals(2, decoded.items.size)
        assertEquals("https://a", decoded.items[0].url)
        assertEquals(null, decoded.items[0].container)
        assertEquals("https://b", decoded.items[1].url)
        assertEquals("video/mp4", decoded.items[1].container)
        assertEquals(1, decoded.currentIndex)
    }

    @Test
    fun `queue item message deserializes a resolved title and duration when present`() {
        val decoded = Json.decodeFromString(
            QueueItemMessage.serializer(),
            """{"url":"https://a","title":"A Video","durationSecs":125.0}""",
        )
        assertEquals("A Video", decoded.title)
        assertEquals(125.0, decoded.durationSecs)
    }

    @Test
    fun `queue item message deserializes a null title and duration when the daemon hasn't resolved them yet`() {
        val decoded = Json.decodeFromString(QueueItemMessage.serializer(), """{"url":"https://a"}""")
        assertEquals(null, decoded.title)
        assertEquals(null, decoded.durationSecs)
    }

    @Test
    fun `queue state message deserializes a null current index for an empty or unstarted queue`() {
        val decoded = Json.decodeFromString(
            QueueStateMessage.serializer(),
            """{"generationTime":1234,"items":[]}""",
        )
        assertEquals(emptyList<QueueItemMessage>(), decoded.items)
        assertEquals(null, decoded.currentIndex)
    }
}
