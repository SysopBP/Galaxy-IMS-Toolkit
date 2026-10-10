package dev.bluehouse.enablevolte

import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serialize one user operation, publish all its keys together, then reset IMS. */
object CarrierWrites {
    private class Pending(val moder: SubscriptionModer) {
        val values = Bundle()
        var restart = false
    }
    private val lock = ReentrantLock()
    private val transaction = ThreadLocal<MutableMap<Int, Pending>?>()
    val revision = MutableStateFlow(0L)
    val active = MutableStateFlow(false)

    fun <T> atomic(block: () -> T): T = lock.withLock {
        if (transaction.get() != null) return@withLock block()
        val changes = linkedMapOf<Int, Pending>()
        transaction.set(changes)
        active.value = true
        try {
            val result = block()
            transaction.remove()
            changes.values.forEach {
                if (!it.values.isEmpty) it.moder.applyVerified(it.values)
                if (it.restart) it.moder.restartIMSRegistration()
            }
            result
        } finally {
            transaction.remove()
            revision.value += 1
            active.value = false
        }
    }

    fun stage(moder: SubscriptionModer, values: Bundle): Boolean {
        val changes = transaction.get() ?: return false
        changes.getOrPut(moder.subscriptionId) { Pending(moder) }.values.putAll(values)
        return true
    }

    fun restart(moder: SubscriptionModer): Boolean {
        val changes = transaction.get() ?: return false
        changes.getOrPut(moder.subscriptionId) { Pending(moder) }.restart = true
        return true
    }
}
