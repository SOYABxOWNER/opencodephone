package io.github.oustery.opencodephone.core.model

/**
 * What the agent is doing right now, as seen by the app shell. The persistent
 * foreground notification (EnvService) and the sidebar footer render this so
 * a task keeps its status visible while the user is outside the chat screen
 * or outside the app entirely.
 */
enum class AgentActivity {
    /** No prompt is being processed for the active session. */
    IDLE,

    /** The agent is streaming/retrying a run for the active session. */
    RUNNING,
}
