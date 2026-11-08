package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** READY includes both freshly installed and previously installed environments. */
suspend fun observeServerLifecycle(
    ready: Flow<Boolean>,
    configuration: Flow<ServerLaunchConfig?>,
    start: suspend (ServerLaunchConfig) -> Unit,
    stop: suspend () -> Unit,
) {
    combine(ready, configuration) { installed, cfg -> if (installed) cfg else null }
        .distinctUntilChanged()
        .collectLatest { cfg ->
            if (cfg == null) stop() else start(cfg)
        }
}
