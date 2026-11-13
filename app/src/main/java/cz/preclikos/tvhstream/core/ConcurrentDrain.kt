package cz.preclikos.tvhstream.core

import java.util.concurrent.ConcurrentMap

/**
 * Removes every entry and hands its value to [action], safely against concurrent
 * removals. Use instead of `map.values.toList(); map.clear()`: Kotlin's toList()
 * reads size() and then iterator().next(), which throws NoSuchElementException if
 * another thread removes the entry in between (Crashlytics: HtspService.failAll).
 */
inline fun <K : Any, V : Any> ConcurrentMap<K, V>.drainEach(action: (V) -> Unit) {
    for (key in keys) {
        remove(key)?.let(action)
    }
}
