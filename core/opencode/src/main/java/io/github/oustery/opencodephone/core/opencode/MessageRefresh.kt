package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/** Coalesce invalidations, but never restart the timer on every incoming token. */
fun Flow<Unit>.throttledInvalidations(intervalMs: Long = 200): Flow<Unit> {
    require(intervalMs > 0)
    return conflate().transform {
        delay(intervalMs)
        emit(Unit)
    }
}
