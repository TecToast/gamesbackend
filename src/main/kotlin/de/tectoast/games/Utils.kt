package de.tectoast.games

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import net.dv8tion.jda.api.entities.User
private val logger = KotlinLogging.logger {}
val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(webJSON)
    }
}
private val userdataCache = SizeLimitedMap<String, DiscordUser>(50)
suspend fun HttpClient.getUserData(accessToken: String): DiscordUser {
    return userdataCache.getOrPut(accessToken) {
        logger.info("Fetching user data for $accessToken")
        getWithToken("https://discord.com/api/users/@me", accessToken)
    }
}

suspend inline fun <reified T> HttpClient.getWithToken(url: String, accessToken: String): T {
    return get(url) {
        header("Authorization", "Bearer $accessToken")
    }.body()
}

class SizeLimitedMap<K, V>(private val maxSize: Int = 5) : LinkedHashMap<K, V>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > maxSize
    }
}


@Serializable
data class DiscordUser(
    val id: Long,
    val username: String,
    @SerialName("global_name")
    val displayName: String,
    val avatar: String?
) {
    fun emolga(): DiscordUser = this.copy(avatar = avatar?.let {
        "https://cdn.discordapp.com/avatars/${id}/${
            it + if (it.startsWith("a_")) ".gif" else ".png"
        }"
    } ?: String.format(User.DEFAULT_AVATAR_URL, ((id shr 22) % 6).toString())
    )
}
