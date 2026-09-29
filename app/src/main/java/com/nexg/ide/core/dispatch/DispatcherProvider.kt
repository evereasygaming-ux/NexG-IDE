package com.nexg.ide.core.dispatch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatchers (PLAN.MD R13).
 *
 * Exists so any class that launches coroutines takes its dispatcher from here
 * instead of hardcoding `Dispatchers.IO`. That is what lets a unit test replace
 * IO with a test dispatcher and assert on ordering, and it is the mechanism
 * that would later let a single-threaded/constrained mode be honoured on
 * low-RAM devices.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}
