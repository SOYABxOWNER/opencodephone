package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response

/** Keeps cancellation attached until the entire response body has been consumed. */
suspend fun <T> Call.consumeCancellable(block: (Response) -> T): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { this@consumeCancellable.cancel() }
        }
        try {
            ensureActive()
            execute().use(block)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            withContext(NonCancellable) { cancellation.cancelAndJoin() }
        }
    }
}
