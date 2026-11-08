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
    enabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
) {
    combine(ready, configuration, enabled) { installed, cfg, running -> if (installed && running) cfg else null }
        .distinctUntilChanged()
        .collectLatest { cfg ->
            if (cfg == null) stop() else start(cfg)
        }
}
