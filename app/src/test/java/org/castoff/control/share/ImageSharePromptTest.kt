package org.castoff.control.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the image-share prompt's pure logic: MIME-type filtering, "at least
 * one option" send-enablement, and the final Toast summary text -- the same
 * split [ControlUiStateTest]/[QueueItemDisplayTest] use for the control
 * screen's derived state.
 */
class ImageSharePromptTest {

    @Test
    fun `isImageMimeType accepts any image subtype and rejects everything else`() {
        assertTrue(isImageMimeType("image/png"))
        assertTrue(isImageMimeType("image/jpeg"))
        assertFalse(isImageMimeType("video/mp4"))
        assertFalse(isImageMimeType("text/plain"))
        assertFalse(isImageMimeType(null))
    }

    @Test
    fun `canSend requires at least one of add-to-queue or set-as-wallpaper`() {
        val neither = ImageShareUiState(imageCount = 1, addToQueue = false, setAsWallpaper = false)
        val queueOnly = ImageShareUiState(imageCount = 1, addToQueue = true, setAsWallpaper = false)
        val wallpaperOnly = ImageShareUiState(imageCount = 1, addToQueue = false, setAsWallpaper = true)
        val both = ImageShareUiState(imageCount = 1, addToQueue = true, setAsWallpaper = true)

        assertFalse(neither.canSend)
        assertTrue(queueOnly.canSend)
        assertTrue(wallpaperOnly.canSend)
        assertTrue(both.canSend)
    }

    @Test
    fun `canSend is false while a send is already in flight even with an option checked`() {
        val sending = ImageShareUiState(imageCount = 1, addToQueue = true, isSending = true)
        assertFalse(sending.canSend)
    }

    @Test
    fun `shareResultSummary reports full success with correct singular-plural wording`() {
        assertEquals("Shared 1 image", shareResultSummary(succeeded = 1, failed = 0))
        assertEquals("Shared 3 images", shareResultSummary(succeeded = 3, failed = 0))
    }

    @Test
    fun `shareResultSummary reports total failure with correct singular-plural wording`() {
        assertEquals("Failed to share the image", shareResultSummary(succeeded = 0, failed = 1))
        assertEquals("Failed to share 2 images", shareResultSummary(succeeded = 0, failed = 2))
    }

    @Test
    fun `shareResultSummary reports a partial success with counts of each`() {
        assertEquals("Shared 2 of 3 images (1 failed)", shareResultSummary(succeeded = 2, failed = 1))
    }
}
