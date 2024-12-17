package de.tectoast.games

import com.mongodb.client.model.Filters
import de.tectoast.games.db.BackendBase
import de.tectoast.games.db.FrontEndBase
import de.tectoast.games.utils.ExpiringMap
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import net.dv8tion.jda.api.entities.User
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import kotlin.reflect.KProperty1

private val logger = KotlinLogging.logger {}
val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(webJSON)
    }
}
private val userdataCache = SizeLimitedMap<String, DiscordUser>(50)
suspend fun HttpClient.getUserData(accessToken: String): DiscordUser {
    return userdataCache.getOrPut(accessToken) {
        logger.info("Fetching user data for ${accessToken.take(5)}")
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

suspend fun MultiPartData.readString(name: String): String? {
    val part = readPart() as? PartData.FormItem ?: return null
    if (part.name != name) return null
    return part.value.encodeURLPathPart()
}

suspend fun ApplicationCall.badReq() = respond(HttpStatusCode.BadRequest)


suspend fun <T : Any> ApplicationCall.findQuizData(coll: CoroutineCollection<T>): T? {
    val session = sessionOrUnauthorized() ?: return null
    val data = coll.findOne(
        and(Filters.eq("user", session.userId), Filters.eq("id", parameters["id"]!!))
    ) ?: run {
        respond(HttpStatusCode.NotFound, "No data for session and user found")
        return null
    }
    return data
}

suspend fun <T : BackendBase> ApplicationCall.findMyQuizzes(coll: CoroutineCollection<T>): List<String> {
    val session = sessionOrUnauthorized() ?: return emptyList()
    return coll.find(Filters.eq("user", session.userId)).toList().map { it.id }
}

inline fun <B : BackendBase, reified F : FrontEndBase<U>, U> Route.createDefaultRoutes(
    coll: CoroutineCollection<B>,
    dataCache: ExpiringMap<String, U>,
    updateMap: Map<KProperty1<B, *>, KProperty1<F, *>>,
    crossinline onEachUser: B.(U) -> Unit = {},
    crossinline frontEndMapper: (B) -> F,
    crossinline backendSupplier: () -> B
) {
    get("/data/{id}") {
        val data = call.findQuizData(coll) ?: return@get
        call.respond(frontEndMapper(data).apply<F> { store(dataCache, data.participants) { u -> data.onEachUser(u) } })
    }
    get("/my") {
        call.respond(call.findMyQuizzes(coll))
    }
    post("/create") {
        val session = call.sessionOrUnauthorized() ?: return@post
        val data = call.receiveText()
        call.respond(runCatching {
            coll.insertOne(
                backendSupplier().apply {
                    user = session.userId
                    id = data
                }
            )
        }.fold(onSuccess = { HttpStatusCode.Created }, onFailure = { HttpStatusCode.BadRequest })
        )
    }


    post("/update/{id}") {
        val data = call.findQuizData(coll) ?: return@post
        val newData = call.receive<F>()
        call.respond(runCatching {
            coll.updateOne(
                and(Filters.eq("user", data.user), Filters.eq("id", data.id)), set(
                    *updateMap.map { (backend, frontend) ->
                        backend setTo frontend.get(newData)
                    }.toTypedArray(),
                    BackendBase::participants setTo newData.participants.keys.toList()
                )
            )
        }.fold(onSuccess = { HttpStatusCode.OK }, onFailure = {
            it.printStackTrace()
            HttpStatusCode.BadRequest
        })
        )
    }
}
