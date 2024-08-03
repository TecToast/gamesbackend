package de.tectoast.games.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

class ExpiringMap<K, V>(private val duration: Duration, private val updateFunction: suspend (List<K>) -> List<V>) {
    private val internalMap: MutableMap<K, V> = mutableMapOf()
    private val lastUpdate = mutableMapOf<K, Instant>()
    suspend fun get(key: K): V {
        val currentTime = Clock.System.now()
        val lastUpdate = lastUpdate[key]
        if (lastUpdate == null || lastUpdate + duration < currentTime) {
            val value = updateFunction(listOf(key))[0]
            internalMap[key] = value
            this.lastUpdate[key] = currentTime
            return value
        }
        return internalMap[key]!!
    }

    suspend fun getAll(list: List<K>): Map<K, V> {
        val currentTime = Clock.System.now()
        val toUpdate = list.filter { key ->
            val lastUpdate = lastUpdate[key]
            lastUpdate == null || lastUpdate + duration < currentTime
        }
        if (toUpdate.isNotEmpty()) {
            val updated = updateFunction(toUpdate)
            toUpdate.forEachIndexed { index, key ->
                internalMap[key] = updated[index]
                lastUpdate[key] = currentTime
            }
        }
        return list.associateWith { internalMap[it]!! }
    }
}
