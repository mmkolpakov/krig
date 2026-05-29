package space.kscience.krig.concurrency

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

public suspend fun <T> StateFlow<T>.waitUntil(predicate: (T) -> Boolean): T =
    first(predicate)
