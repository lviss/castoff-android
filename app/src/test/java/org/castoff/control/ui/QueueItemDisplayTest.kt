package org.castoff.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue row's title/length text is derived from [QueueItemUi], which the
 * daemon's background lookup can fill in after the item is already shown
 * (see the daemon's `QueueState`) -- these pin the row text for both the
 * not-yet-resolved and resolved cases.
 */
class QueueItemDisplayTest {

    @Test
    fun `a row with only a url falls back to the url as its title and has no length`() {
        val item = QueueItemUi(url = "https://example.com/video")

        assertEquals("https://example.com/video", queueItemDisplayTitle(item))
        assertNull(queueItemDisplayDuration(item))
    }

    @Test
    fun `a row with a resolved title and duration shows both`() {
        val item = QueueItemUi(
            url = "https://example.com/video",
            title = "A Great Video",
            durationSecs = 125.0,
        )

        assertEquals("A Great Video", queueItemDisplayTitle(item))
        assertEquals("2:05", queueItemDisplayDuration(item))
    }
}
