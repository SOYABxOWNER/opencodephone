package io.github.oustery.opencodephone.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Small helper to build ViewModel factories for manual DI graphs. */
object VmFactory {
    // inline+reified lets `initializer` capture the concrete ViewModel type.
    inline fun <reified T : ViewModel> of(crossinline create: () -> T): ViewModelProvider.Factory =
        viewModelFactory { initializer { create() } }
}

/** Minimal POSIX shell quoting for commands built from user input. */
fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"
