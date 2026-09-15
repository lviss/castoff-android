package org.castoff.control.ui

/** Which of the three things the user needs to know the status connection is doing. */
enum class ConnectionPhase {
    /** No TV box configured yet: there is nothing to connect to. */
    NOT_CONFIGURED,

    /** A connection attempt is in flight. */
    CONNECTING,

    /** Talking to the daemon: pushes arrive on this link. */
    CONNECTED,

    /** The link is down; [ConnectionStatus.reason] says why, and a Connect button is offered. */
    NOT_CONNECTED,
}

/**
 * UI-only snapshot of the status connection, shown honestly at the top of the
 * control screen. Deliberately a small value type with the user-facing wording
 * in one place ([headline]/[detail]) so what the screen claims about the link
 * is unit-testable and can't drift between call sites.
 */
data class ConnectionStatus(
    val phase: ConnectionPhase,
    val host: String = "",
    val port: Int = 0,
    /** Plain-language cause of the last failure; only meaningful when [phase] is [ConnectionPhase.NOT_CONNECTED]. */
    val reason: String? = null,
) {
    /** One line at a glance: connected, connecting, or not connected (and to what). */
    val headline: String
        get() = when (phase) {
            ConnectionPhase.NOT_CONFIGURED -> "No TV box set"
            ConnectionPhase.CONNECTING -> if (host.isBlank()) "Connecting…" else "Connecting to $host:$port…"
            ConnectionPhase.CONNECTED -> "Connected to $host:$port"
            ConnectionPhase.NOT_CONNECTED -> "Not connected to $host:$port"
        }

    /** Secondary line: what to do about it, or why it failed. Null when nothing more need be said. */
    val detail: String?
        get() = when (phase) {
            ConnectionPhase.NOT_CONFIGURED -> "Enter the TV box's host and port below, then tap Save"
            ConnectionPhase.NOT_CONNECTED -> reason
            else -> null
        }

    /** Whether a manual Connect action makes sense (a host is configured but the link is down). */
    val canConnect: Boolean get() = phase == ConnectionPhase.NOT_CONNECTED

    companion object {
        val notConfigured = ConnectionStatus(ConnectionPhase.NOT_CONFIGURED)

        fun connecting(host: String, port: Int) =
            ConnectionStatus(ConnectionPhase.CONNECTING, host, port)

        fun connected(host: String, port: Int) =
            ConnectionStatus(ConnectionPhase.CONNECTED, host, port)

        fun notConnected(host: String, port: Int, reason: String) =
            ConnectionStatus(ConnectionPhase.NOT_CONNECTED, host, port, reason)
    }
}
