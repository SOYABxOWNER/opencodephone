package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAutostartTest {
    @Test
    fun `fresh setup, existing install, config edits, remote mode and reinstall reconcile`() = runTest {
        val ready = MutableStateFlow(false)
        val config = MutableStateFlow<ServerLaunchConfig?>(ServerLaunchConfig(4096, "opencode", ""))
        val actions = mutableListOf<String>()
        backgroundScope.launch {
            observeServerLifecycle(ready, config,
                start = { actions += "start:${it.port}" }, stop = { actions += "stop" })
        }
        runCurrent()
        assertEquals(listOf("stop"), actions)
        ready.value = true
        runCurrent()
        assertEquals(listOf("stop", "start:4096"), actions)
        ready.value = true
        runCurrent()
        assertEquals(2, actions.size)
        config.value = config.value!!.copy(port = 4196)
        runCurrent()
        config.value = null // remote mode must never launch a local child
        runCurrent()
        ready.value = false
        runCurrent()
        config.value = ServerLaunchConfig(4096, "opencode", "")
        runCurrent()
        ready.value = true
        runCurrent()
        assertEquals(listOf("stop", "start:4096", "start:4196", "stop", "start:4096"), actions)
    }

    @Test
    fun `manual stop survives configuration and readiness changes until explicit start`() = runTest {
        val ready = MutableStateFlow(true)
        val config = MutableStateFlow<ServerLaunchConfig?>(ServerLaunchConfig(4096, null, null))
        val enabled = MutableStateFlow(true)
        val actions = mutableListOf<String>()
        backgroundScope.launch {
            observeServerLifecycle(ready, config,
                start = { actions += "start:${it.port}" }, stop = { actions += "stop" }, enabled = enabled)
        }
        runCurrent()
        enabled.value = false
        runCurrent()
        config.value = ServerLaunchConfig(4196, null, null)
        runCurrent()
        ready.value = false
        runCurrent()
        ready.value = true
        runCurrent()
        assertEquals(listOf("start:4096", "stop"), actions)
        enabled.value = true
        runCurrent()
        assertEquals(listOf("start:4096", "stop", "start:4196"), actions)
    }

    @Test
    fun `already installed on app launch starts without another bootstrap transition`() = runTest {
        val starts = mutableListOf<Int>()
        backgroundScope.launch {
            observeServerLifecycle(MutableStateFlow(true),
                MutableStateFlow(ServerLaunchConfig(4096, null, null)),
                start = { starts += it.port }, stop = { })
        }
        runCurrent()
        assertEquals(listOf(4096), starts)
    }
}
