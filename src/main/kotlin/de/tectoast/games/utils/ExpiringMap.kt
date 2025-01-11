package de.tectoast.games.utils

import de.tectoast.games.discord.jda
import de.tectoast.games.httpClient
import de.tectoast.games.webJSON
import dev.minn.jda.ktx.coroutines.await
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
)

data class SessionUpdaterData(
    val accessToken: String,
    val refreshToken: String,
    val expires: Long,
)

class OAuthExpiringMap<D : OAuthSession, V>(
    val clientId: String,
    val clientSecret: String,
    val sessionUpdater: (D, SessionUpdaterData) -> D,
    val updater: suspend (String) -> V
) {
    suspend inline fun <reified S : OAuthSession> get(call: ApplicationCall, data: S): V {
        val currentTime = Clock.System.now()
        val accessToken = if (data.expires + 600000 < currentTime.toEpochMilliseconds()) {
            var response: AccessTokenResponse? = null

            // Fetch new access token
            val res = httpClient.submitForm("https://discord.com/api/v10/oauth2/token",
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", data.refreshToken)
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                }) {
            }.bodyAsText()
            response = webJSON.decodeFromString(res)!!
            @Suppress("UNCHECKED_CAST")
            call.sessions.set<S>(
                sessionUpdater(
                    data as D,
                    SessionUpdaterData(
                        response.accessToken,
                        response.refreshToken,
                        currentTime.toEpochMilliseconds() + response.expiresIn * 1000
                    )
                ) as S
            )
            response.accessToken
        } else data.accessToken
        return updater(accessToken)
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
