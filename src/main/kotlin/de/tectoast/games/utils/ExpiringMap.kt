package de.tectoast.games.utils

import de.tectoast.games.discord.jda
import de.tectoast.games.httpClient
import dev.minn.jda.ktx.coroutines.await
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

interface OAuthSession {
    val accessToken: String
    val refreshToken: String
    val expires: Long
}

@Serializable
class RefreshRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
)

class OAuthExpiringMap<D : OAuthSession, V>(
    val duration: Duration,
    val clientId: String,
    val clientSecret: String,
    val sessionUpdater: (D, String, Long) -> D,
    val updater: suspend (String) -> V
) {
    val internalMap: MutableMap<String, V> = mutableMapOf()
    val lastUpdate = mutableMapOf<String, Instant>()
    suspend inline fun <reified S : OAuthSession> get(call: ApplicationCall, data: S): V {
        val key = data.refreshToken
        val currentTime = Clock.System.now()
        val lastUpdate = lastUpdate[key]
        if (lastUpdate == null || lastUpdate + duration < currentTime) {
            var response: AccessTokenResponse? = null
            val accessToken =
                if (data.expires + 600000 < currentTime.toEpochMilliseconds()) {
                    // Fetch new access token
                    response = httpClient.get("https://discord.com/api/oauth2/token") {
                        setBody(
                            RefreshRequest(
                                refreshToken = data.refreshToken,
                                clientId = clientId,
                                clientSecret = clientSecret
                            )
                        )
                    }.body<AccessTokenResponse>()
                    call.sessions.set<S>(sessionUpdater(data as D, response.accessToken, currentTime.toEpochMilliseconds() + response.expiresIn) as S)
                    response.accessToken
                } else {
                    data.accessToken
                }
            val value = updater(accessToken)
            internalMap[key] = value
            this.lastUpdate[key] = currentTime
            return value
        }
        return internalMap[key]!!
    }
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
