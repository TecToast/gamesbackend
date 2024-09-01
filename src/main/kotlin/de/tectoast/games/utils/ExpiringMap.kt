package de.tectoast.games.utils

import de.tectoast.games.discord.jda
import dev.minn.jda.ktx.coroutines.await
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import net.dv8tion.jda.api.entities.Member
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

const val GUILD_ID = 1036657324909146153
inline fun <T> createDataCache(crossinline mapper: (CacheData) -> T) = ExpiringMap(1.days) { list ->
    jda.getGuildById(GUILD_ID)!!.findMembers { mem -> mem.id in list }.await().sortedBy { list.indexOf(it.id) }.map {
        mapper(CacheData(it))
    }
}

data class CacheData(val member: Member) {
    val avatarUrl = member.user.effectiveAvatarUrl.substringBeforeLast(".") + ".png?size=512"
    val effectiveName = member.user.effectiveName
}
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
