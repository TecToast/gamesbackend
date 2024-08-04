package de.tectoast.games

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.tectoast.games.discord.initJDA
import de.tectoast.games.jeopardy.jeopardy
import de.tectoast.games.wizard.WizardSession
import de.tectoast.games.wizard.initWizard
import de.tectoast.games.wizard.wizard
import de.tectoast.games.jeopardy.mediaBaseDir as jeopardyMedia
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.plugins.callloging.*
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
import java.time.Duration
import kotlin.system.exitProcess

lateinit var config: Config

lateinit var discordAuthDB: Database
lateinit var wizardDB: Database

fun main() {
    config = loadConfig("config.json") { Config() }
    initDirectories()
    initJDA(config)
    initMongo()
    discordAuthDB = Database.connect(HikariDataSource(HikariConfig().apply { jdbcUrl = config.mysqlUrl }))
    initWizard()
    embeddedServer(CIO, port = 9934, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

private fun initDirectories() {
    val media = File("media")
    media.mkdir()
    jeopardyMedia = media.resolve("jeopardy")
    jeopardyMedia.mkdir()

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
                    if(user.id !in config.whitelisted) {
                        call.respondRedirect("/error/notwhitelisted")
                        return@get
                    }
                    call.sessions.set(
                        UserSession(
                            accessToken,
                            principal.refreshToken!!,
                            principal.expiresIn,
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
            route("/jeopardy") {
                install(apiGuard)
                jeopardy()
            }
            get("/mygames") {
                val session = call.sessionOrNull()
                if (session == null) {
                    call.respond(emptyList<GameMeta>())
                    return@get
                }
                call.respond(listOf(GameMeta("Jeopardy", "/jeopardy")))
            }
            get("/reloadconfig") {
                val session = call.sessionOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                if(session.userId != 175910318608744448) return@get call.respond(HttpStatusCode.NotFound)
                config = loadConfig("config.json") { Config() }
                call.respond(HttpStatusCode.OK, "Done!")
            }
        }
        route("/wizard") {
            wizard()
        }
    }
}

@Serializable
data class GameMeta(val displayName: String, val url: String)

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
            cookie.extensions["SameSite"] = "None"
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
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
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

fun ApplicationCall.sessionOrNull() = sessions.get<UserSession>()

fun ApplicationCall.sessionOrUnauthorized(): UserSession? {
    return sessionOrNull() ?: run {
        response.status(HttpStatusCode.Unauthorized)
        null
    }
}

@Serializable
data class UserSession(val accessToken: String, val refreshToken: String, val expires: Long, val userId: Long)

@Serializable
data class Config(
    val oauth2Secret: String = "secret",
    val devMode: Boolean = false,
    val discordBotToken: String = "secret",
    val mysqlUrl: String = "secret",
    val whitelisted: Set<Long> = emptySet()
)
