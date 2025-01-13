package de.tectoast.games

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.tectoast.games.discord.initJDA
import de.tectoast.games.jeopardy.jeopardy
import de.tectoast.games.musicquiz.musicQuiz
import de.tectoast.games.nobodyisperfect.nobodyIsPerfect
import de.tectoast.games.utils.OAuthExpiringMap
import de.tectoast.games.utils.OAuthSession
import de.tectoast.games.wizard.WizardSession
import de.tectoast.games.wizard.wizard
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.slf4j.event.Level
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import de.tectoast.games.jeopardy.mediaBaseDir as jeopardyMedia
import de.tectoast.games.nobodyisperfect.mediaBaseDir as nobodyIsPerfectMedia

lateinit var config: Config

lateinit var discordAuthDB: Database

val nameCache by lazy {
    OAuthExpiringMap<UserSession, String>(
        clientId = "723829878755164202",
        clientSecret = config.oauth2Secret,
        sessionUpdater = { session, data ->
            session.copy(
                accessToken = data.accessToken,
                refreshToken = data.refreshToken,
                expires = data.expires
            )
        },
    ) { accessToken ->
        httpClient.getUserData(accessToken).displayName
    }
}

val configPath get() = System.getenv("GAMESBACKEND_CONFIG_FILE") ?: "config.json"

fun main() {
    config = loadConfig(configPath) { Config() }
    initDirectories()
    initJDA(config)
    if (config.mysqlUrl != "secret") {
        initMongo(config.mongoDb)
        discordAuthDB = Database.connect(HikariDataSource(HikariConfig().apply { jdbcUrl = config.mysqlUrl }))
    }
    embeddedServer(CIO, port = 9934, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

private fun initDirectories() {
    val media = File("/app/media")
    media.mkdir()
    jeopardyMedia = media.resolve("jeopardy")
    jeopardyMedia.mkdir()
    nobodyIsPerfectMedia = media.resolve("nobodyisperfect")
    nobodyIsPerfectMedia.mkdir()
}

private val configJson = Json {
    encodeDefaults = true
}

private inline fun <reified T> loadConfig(filename: String, default: () -> T): T {
    val file = File(filename)
    if (!file.exists()) {
        file.writeText(configJson.encodeToString(default()))
        exitProcess(0)
    }
    return configJson.decodeFromString(file.readText())
}

fun Application.module() {
    installPlugins()
    installAuth(config)
    routing {
        route("/api") {
            authenticate("auth-oauth-discord") {
                get("/login") {}
                get("/discordauth") {
                    val principal: OAuthAccessTokenResponse.OAuth2 = call.principal() ?: run {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    val accessToken = principal.accessToken
                    val user = httpClient.getUserData(accessToken)
                    if (user.id !in config.permissions) {
                        call.respondRedirect("/error/notwhitelisted")
                        return@get
                    }
                    call.sessions.set(
                        UserSession(
                            accessToken,
                            principal.refreshToken!!,
                            System.currentTimeMillis() + principal.expiresIn * 1000,
                            user.id
                        )
                    )
                    call.respondRedirect(if (config.devMode) "http://localhost:3000/" else "https://games.tectoast.de/")
                }
            }
            get("/userdata") {
                val session = call.sessionOrUnauthorized() ?: return@get
                call.respond(httpClient.getUserData(session.accessToken).emolga())
            }
            get("/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("https://games.tectoast.de/")
            }
            if (config.enabledGames.contains("jeopardy")) {
                route("/jeopardy") {
                    install(apiGuard)
                    jeopardy()
                }
            }
            if (config.enabledGames.contains("musicquiz")) {
                route("/musicquiz") {
                    install(apiGuard)
                    musicQuiz()
                }
            }
            if (config.enabledGames.contains("wizard")) {
                route("/wizard") {
                    install(apiGuard)
                    wizard()
                }
            }
            if (config.enabledGames.contains("nobodyisperfect")) {
                route("/nobodyisperfect") {
                    install(apiGuard)
                    nobodyIsPerfect()
                }
            }
            get("/mygames") {
                val session = call.sessionOrNull()
                if (session == null) {
                    call.respond(AuthData("", emptyList()))
                    return@get
                }
                call.respond(
                    if (session.userId == 0L) AuthData(
                        "TestUser",
                        allGames.entries.mapNotNull { en -> en.value.takeIf { en.key in config.enabledGames } }) else
                        AuthData(
                            nameCache.get(call, session),
                            config.permissions[session.userId]?.mapNotNull { if (it in config.enabledGames) allGames[it] else null }
                                ?: emptyList()
                        )
                )
            }
            get("/reloadconfig") {
                val session = call.sessionOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                if (session.userId != 175910318608744448) return@get call.respond(HttpStatusCode.NotFound)
                config = loadConfig(configPath) { Config() }
                call.respond(HttpStatusCode.OK, "Done!")
            }

        }
    }
}

@Serializable
data class GameMeta(val displayName: String, val url: String)

@Serializable
data class AuthData(val name: String, val games: List<GameMeta>)

val allGames = mapOf(
    "jeopardy" to GameMeta("Jeopardy", "/jeopardy/config"),
    "musicquiz" to GameMeta("MusicQuiz", "/musicquiz/config"),
    "wizard" to GameMeta("Wizard", "/wizard"),
    "nobodyisperfect" to GameMeta("Nobody is perfect", "/nobodyisperfect")
)

private fun Application.installAuth(config: Config) {
    authentication {
        oauth("auth-oauth-discord") {
            urlProvider =
                { if (config.devMode) "http://localhost:3000/api/discordauth" else "https://games.tectoast.de/api/discordauth" }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "discord",
                    authorizeUrl = "https://discord.com/oauth2/authorize",
                    accessTokenUrl = "https://discord.com/api/oauth2/token",
                    requestMethod = HttpMethod.Post,
                    clientId = "723829878755164202",
                    clientSecret = config.oauth2Secret,
                    defaultScopes = listOf("identify"),
                    extraAuthParameters = listOf("grant_type" to "authorization_code")
                )
            }
            client = httpClient
        }
    }
}


private fun Application.installPlugins() {
    install(Sessions) {
        cookie<UserSession>("user_session", DiscordAuthDB) {
            cookie.extensions["SameSite"] = "Lax"
            cookie.httpOnly = true
            cookie.secure = !config.devMode
        }
        cookie<WizardSession>("wiz") {
            cookie.extensions["SameSite"] = "lax"
        }
    }
    install(ContentNegotiation) {
        json(webJSON)
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(WebSockets) {
        timeout = 15.seconds
        pingPeriod = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    install(IgnoreTrailingSlash)
}

val webJSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

val apiGuard = createRouteScopedPlugin("AuthGuard") {
    onCall { call ->
        call.sessionOrUnauthorized()
    }
}

fun ApplicationCall.sessionOrNull() =
    (if (config.mysqlUrl == "secret") null else sessions.get<UserSession>()) ?: if (config.devMode) UserSession(
        "dev",
        "dev",
        0,
        0
    ) else null

suspend fun ApplicationCall.sessionOrUnauthorized(): UserSession? {
    return sessionOrNull() ?: run {
        respond(HttpStatusCode.Unauthorized)
        null
    }
}

@Serializable
data class UserSession(
    override val accessToken: String,
    override val refreshToken: String,
    override val expires: Long,
    val userId: Long
) : OAuthSession

@Serializable
data class Config(
    val enabledGames: Set<String> = setOf("jeopardy", "musicquiz", "wizard", "nobodyisperfect"),
    val oauth2Secret: String = "secret",
    val devMode: Boolean = true,
    val discordBotToken: String = "secret",
    val mysqlUrl: String = "secret",
    val permissions: Map<Long, Set<String>> = emptyMap(),
    val mongoDb: MongoConfig = MongoConfig()
)

@Serializable
data class MongoConfig(
    val url: String = "",
    val dbName: String = "games"
)