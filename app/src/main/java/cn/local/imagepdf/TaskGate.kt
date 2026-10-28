package cn.local.imagepdf

import java.util.concurrent.atomic.AtomicReference

/** One memory-intensive operation per process; stale finally blocks cannot unlock a newer task. */
class TaskGate {
    private val active = AtomicReference<Lease?>(null)
    val owner: String? get() = active.get()?.owner

    fun tryAcquire(owner: String): Lease? {
        val lease = Lease(owner)
        return if (active.compareAndSet(null, lease)) lease else null
    }

    inner class Lease internal constructor(val owner: String) : AutoCloseable {
        override fun close() { active.compareAndSet(this, null) }
    }
}
