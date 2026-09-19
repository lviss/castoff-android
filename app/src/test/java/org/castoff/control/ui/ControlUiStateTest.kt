package org.castoff.control.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next/Previous enablement is derived straight from the daemon-reported
 * [ControlUiState.queueCurrentIndex] and [ControlUiState.queueItems] bounds
 * (see the daemon's `QueueState`), never guessed locally -- these pin that
 * derivation at every edge of the queue.
 */
class ControlUiStateTest {

    private fun stateWith(items: List<String>, currentIndex: Int?) = ControlUiState(
        hostAddress = "10.0.2.2",
        hostPort = "46899",
        isPlaying = false,
        volume = 0.5f,
        statusMessage = null,
        queueItems = items.map { QueueItemUi(it) },
        queueCurrentIndex = currentIndex,
    )

    @Test
    fun `an empty queue allows neither direction`() {
        val state = stateWith(items = emptyList(), currentIndex = null)

        assertFalse(state.canQueueJumpForward)
        assertFalse(state.canQueueJumpBackward)
    }

    @Test
    fun `the first item allows forward but not backward`() {
        val state = stateWith(items = listOf("a", "b"), currentIndex = 0)

        assertTrue(state.canQueueJumpForward)
        assertFalse(state.canQueueJumpBackward)
    }

    @Test
    fun `a middle item allows both directions`() {
        val state = stateWith(items = listOf("a", "b", "c"), currentIndex = 1)

        assertTrue(state.canQueueJumpForward)
        assertTrue(state.canQueueJumpBackward)
    }

    @Test
    fun `the last item allows backward but not forward`() {
        val state = stateWith(items = listOf("a", "b"), currentIndex = 1)

        assertFalse(state.canQueueJumpForward)
        assertTrue(state.canQueueJumpBackward)
    }

    @Test
    fun `a null current index allows neither direction even with items queued`() {
        val state = stateWith(items = listOf("a", "b"), currentIndex = null)

        assertFalse(state.canQueueJumpForward)
        assertFalse(state.canQueueJumpBackward)
    }
}
