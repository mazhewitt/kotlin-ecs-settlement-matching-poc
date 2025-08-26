package org.example.settlement.matching

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Simple file-backed newline-delimited queue adapter.
 * - Input queue tails a file and emits new lines parsed as T.
 * - Output queue appends formatted lines.
 *
 * Domain/ECS note: This is an integration adapter to feed the matching ECS "tick" from external scripts.
 */
class FileLineInQueue<T>(
    private val path: Path,
    private val parse: (String) -> T?
) : MessageQueue<T> {
    private var position: Long = 0L

    init {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.createFile(path)
        }
        position = Files.size(path)
    }

    override fun offer(msg: T) {
        // Not used for input queues in this adapter.
        throw UnsupportedOperationException("offer not supported for FileLineInQueue")
    }

    override fun poll(): T? {
        var result: T? = null
        drainTo { t -> if (result == null) result = t }
        return result
    }

    override fun isEmpty(): Boolean {
        // Conservative; we don't track availability without reading.
        return true
    }

    override fun drainTo(consumer: (T) -> Unit) {
        val size = Files.size(path)
        if (size <= position) return
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            reader.skip(position)
            var line = reader.readLine()
            var bytesRead = 0L
            while (line != null) {
                bytesRead += (line.toByteArray(StandardCharsets.UTF_8).size + 1) // +1 for newline
                parse(line)?.let(consumer)
                line = reader.readLine()
            }
            position += bytesRead
        }
    }
}

class FileLineOutQueue<T>(
    private val path: Path,
    private val format: (T) -> String
) : MessageQueue<T> {
    init {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.createFile(path)
        }
    }

    override fun offer(msg: T) {
        val line = format(msg) + "\n"
        Files.write(
            path,
            line.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        )
    }

    override fun poll(): T? {
        throw UnsupportedOperationException("poll not supported for FileLineOutQueue")
    }

    override fun isEmpty(): Boolean = true
}
