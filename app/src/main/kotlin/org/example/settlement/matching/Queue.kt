package org.example.settlement.matching

import java.util.ArrayDeque

/**
 * Minimal message queue abstraction for local development.
 * For tests, we use the in-memory implementation. For local IO, a file/named-pipe adapter can be added.
 */
interface MessageQueue<T> {
    fun offer(msg: T)
    fun poll(): T?
    fun isEmpty(): Boolean
    fun drainTo(consumer: (T) -> Unit) {
        while (true) {
            val m = poll() ?: break
            consumer(m)
        }
    }
}

class InMemoryQueue<T> : MessageQueue<T> {
    private val q = ArrayDeque<T>()
    override fun offer(msg: T) { q.addLast(msg) }
    override fun poll(): T? = if (q.isEmpty()) null else q.removeFirst()
    override fun isEmpty(): Boolean = q.isEmpty()
}

/**
 * Placeholder for a named-pipe backed queue (macOS/Linux).
 * ECS note: would serve as an external source feeding into our ECS "tick" via the orchestrator's process step.
 * Not implemented in tests; prefer InMemoryQueue for determinism.
 */
class NamedPipeQueue // TODO: Provide a String-based adapter using mkfifo and java.nio once needed.
