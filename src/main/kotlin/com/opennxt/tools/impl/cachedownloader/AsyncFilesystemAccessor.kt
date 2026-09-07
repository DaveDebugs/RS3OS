package com.opennxt.tools.impl.cachedownloader

import com.opennxt.ext.getCrc32
import com.opennxt.filesystem.Filesystem
import com.opennxt.util.Whirlpool
import java.io.Closeable
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AsyncFilesystemAccessor(val filesystem: Filesystem) : Runnable, Closeable {

    private val running = AtomicBoolean(true)

    /**
     * The queue was a `LinkedList`, written by the request-handler thread via
     * `addLast` and drained by THIS class's own thread via `pollFirst`, with no
     * synchronization anywhere. `LinkedList` is not thread-safe, and it is not
     * safe for single-producer/single-consumer either: `addLast` and
     * `pollFirst` both mutate `size` and both touch the head/tail links, so
     * concurrent calls can lose an operation, resurrect one, or leave the list
     * structurally corrupt.
     *
     * The consequence is quiet and nasty. A lost operation is a lost ARCHIVE
     * WRITE - the download reports success, the CompletableFuture never
     * completes or completes for a node that was dropped, and the cache ends up
     * missing files that nothing will ever report as missing. On a run that
     * queued 227,060 operations across 8 worker threads, that is not a
     * theoretical race.
     *
     * ConcurrentLinkedQueue is the drop-in fix, with one trap: its `size()` is
     * O(n), and `pendingOperations()` is called once per second for every
     * worker from inside `createSnapshot`. With a six-figure backlog that would
     * turn a status line into a full traversal of the queue, several times a
     * second, while holding the request handler's lock. So the count is tracked
     * separately in an AtomicInteger and `size()` is never called.
     */
    private val operations = ConcurrentLinkedQueue<IOOperation<*>>()
    private val queueDepth = AtomicInteger(0)

    fun pendingOperations(): Int = queueDepth.get()

    private fun <T> enqueue(operation: IOOperation<T>, future: CompletableFuture<T>): CompletableFuture<T> {
        operations.add(operation)
        queueDepth.incrementAndGet()
        return future
    }

    fun write(request: Js5RequestHandler.ArchiveRequest): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        return enqueue(IOOperation.WriteRequestOperation(request, future), future)
    }

    fun write(index: Int, archive: Int, data: ByteArray, version: Int, crc: Int): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        return enqueue(IOOperation.WriteOperation(index, archive, data, version, crc, future), future)
    }

    fun read(index: Int, archive: Int): CompletableFuture<ByteBuffer?> {
        val future = CompletableFuture<ByteBuffer?>()
        return enqueue(IOOperation.ReadOperation(index, archive, future), future)
    }

    override fun close() {
        running.set(false)
    }

    override fun run() {
        try {
            while (running.get()) {
                val operation = operations.poll()
                if (operation == null) {
                    sleep(10)
                    continue
                }
                queueDepth.decrementAndGet()

                try {
                    when (operation) {
                        is IOOperation.ReadOperation -> {
                            val index = operation.index
                            val archive = operation.archive

                            operation.future.complete(
                                if (index == 255) filesystem.readReferenceTable(archive) else filesystem.read(
                                    index,
                                    archive
                                )
                            )
                        }
                        is IOOperation.WriteOperation -> {
                            val index = operation.index
                            val archive = operation.archive
                            val version = operation.version
                            val crc = operation.crc

                            if (index == 255)
                                filesystem.writeReferenceTable(archive, operation.data, version, crc)
                            else
                                filesystem.write(index, archive, operation.data, version, crc)

                            operation.future.complete(Unit)
                        }
                        is IOOperation.WriteRequestOperation -> {
                            if (!operation.request.isCompleted())
                                throw IllegalArgumentException("Attempted to write uncompleted request: ${operation.request}")

                            val table = filesystem.getReferenceTable(operation.index)
                                ?: throw NullPointerException("Reference table for write request not found: ${operation.index}")
                            val entry = table.archives[operation.archive]
                                ?: throw NullPointerException("Reference table entry for write request not found: [${operation.index}, ${operation.archive}]")

                            val buffer = operation.request.buffer
                                ?: throw NullPointerException("Request buffer is missing")

                            val crc = buffer.getCrc32()
                            if (crc != entry.crc)
                                throw IllegalArgumentException("CRC mismatch in [${operation.index}, ${operation.archive}]. Got $crc, expected ${entry.crc}")

                            if (entry.whirlpool != null) {
                                val whirlpool = Whirlpool.getHash(buffer.array(), 0, buffer.limit())
                                if (!Arrays.equals(whirlpool, entry.whirlpool))
                                    throw IllegalArgumentException("Whirlpool mismatch in [${operation.index}, ${operation.archive}]")
                            }

                            val version = entry.version
                            buffer.position(buffer.limit()).limit(buffer.capacity())
                            buffer.put((version shr 8).toByte())
                            buffer.put(version.toByte())

                            filesystem.write(operation.index, operation.archive, buffer.array(), version, crc)

                            operation.future.complete(Unit)
                        }
                    }
                } catch (e: Exception) {
                    operation.future.completeExceptionally(e)
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }

    sealed class IOOperation<T : Any?>(val index: Int, val archive: Int, val future: CompletableFuture<T>) {
        class WriteRequestOperation(
            val request: Js5RequestHandler.ArchiveRequest,
            future: CompletableFuture<Unit>
        ) : IOOperation<Unit>(request.index, request.archive, future)

        class WriteOperation(
            index: Int,
            archive: Int,
            val data: ByteArray,
            val version: Int,
            val crc: Int,
            future: CompletableFuture<Unit>
        ) : IOOperation<Unit>(index, archive, future)

        class ReadOperation(index: Int, archive: Int, future: CompletableFuture<ByteBuffer?>) :
            IOOperation<ByteBuffer?>(index, archive, future)
    }
}