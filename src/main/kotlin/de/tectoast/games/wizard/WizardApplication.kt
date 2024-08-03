package de.tectoast.games.wizard

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.tectoast.games.wizard.model.GameData
import de.tectoast.games.wizard.model.Login
import de.tectoast.games.wizard.model.WSMessage
import de.tectoast.games.wizard.model.WSMessage.*
import de.tectoast.games.wizard.plugins.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database

data class WizardSession(val token: String)

val database = Database.connect(HikariDataSource(HikariConfig().apply {
    jdbcUrl =
        "jdbc:mariadb://localhost/wizard?user=wizard&password=wizard&minPoolSize=1&rewriteBatchedStatements=true"
}))
val logger = KotlinLogging.logger {}
val userService = UserService(database)

fun Route.wizard() {
    singlePageApplication {
        val fileRoot =
            if (developmentMode) "/home/florian/Desktop/IntelliJ/Web/wizard/wizardfront/dist/wizard"
            else "/home/florian/gamesnew/wizard/web"
        logger.info { "FileRoot: $fileRoot" }
        angular(fileRoot)
    }
    post("/login") {
        logger.info { "YEAH LOGIN" }
        val user = call.receive<Login>()
        val token =
            userService.byLogin(user) ?: return@post call.respond(HttpStatusCode.Forbidden)
        call.sessions.set(WizardSession(token))
        call.respond(HttpStatusCode.NoContent)
    }

    post("/registerme") {
        val user = call.receive<Login>()
        logger.info { System.getenv("REGISTER_KEY") }
        if (call.request.authorization() != System.getenv("REGISTER_KEY")) return@post call.respond(
            HttpStatusCode.Forbidden
        )
        userService.register(user)
        call.respond(HttpStatusCode.OK)
    }
    webSocket("/ws") {
        try {
            suspend fun error() = sendWS(
                WSLoginResponse(null)
            )

//                val session = call.sessions.get<WizardSession>() ?: return@webSocket error()
//                val username = userService.byToken(session.token) ?: return@webSocket error()
            val username = usernameProvider.run { getUsername() } ?: return@webSocket error()
            logger.info { "Username: $username" }

            lateinit var game: Game
            SocketManager.register(username, this)
            sendWS(WSLoginResponse(username))
            GameManager.updateOpenGames(this)
            while (true) {
                val msg = runCatching { receiveDeserialized<WSMessage>() }.onFailure {
                    logger.error(it) { "Error on deserialize:" }
                    if (it is ClosedReceiveChannelException) {
                        return@webSocket
                    }
                }.getOrNull()
                when (msg) {
                    null -> {}
                    is CreateGame -> {
                        val g = Game(username)
                        val id = GameManager.register(g)
                        sendWS(GameCreated(id))
                        GameManager.updateOpenGames(null)
                    }

                    is JoinGame -> {
                        game = gameOrRedirectHome(msg.gameID) ?: continue
                        if (game.isRunning) {
                            game.updateLobby()
                            game.sendCurrentState(username)
                        } else {
                            game.addPlayer(username)
                        }
                    }

                    is DeleteGame -> GameManager.removeGame(msg.gameID)
                    else -> {
                        game.handleMessage(this, msg, username)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in Websocket" }
        }
    }
}

val usernameProvider: UsernameProvider = UsernameProvider.Session

sealed class UsernameProvider {
    abstract suspend fun WebSocketServerSession.getUsername(): String?

    data object Session : UsernameProvider() {
        override suspend fun WebSocketServerSession.getUsername(): String? {
            val callSession = call.sessions.get<WizardSession>()
            logger.info { callSession }
            val session = callSession ?: return null
            return userService.byToken(session.token).also { logger.info { it } }
        }
    }

    data object Dev : UsernameProvider() {
        override suspend fun WebSocketServerSession.getUsername(): String? {
            return runCatching { receiveDeserialized<String>() }.onFailure { logger.error(it) { "Receive Dev Token" } }
                .getOrNull()
        }
    }
}

private suspend fun WebSocketServerSession.gameOrRedirectHome(gameID: Int): Game? {
    return GameManager.findGame(gameID) ?: run {
        sendWS(RedirectHome)
        null
    }
}

object GameManager {
    private val games = mutableMapOf<Int, Game>()

    suspend fun updateOpenGames(socket: WebSocketServerSession?) {
        val openGames = OpenGames(games.filter { !it.value.isRunning }.map { GameData(it.value.owner, it.key) })
        socket?.sendWS(openGames) ?: SocketManager.broadcastToEveryConnectedClient(openGames)
    }

    fun findGame(id: Int) = games[id]

    fun register(game: Game): Int {
        var id = 0
        while (id in games) {
            id++
        }
        games[id] = game
        return id
    }

    suspend fun removeGame(id: Int) {
        games.remove(id)?.let {
            it.broadcast(Reset)
            updateOpenGames(null)
        }
    }
}
