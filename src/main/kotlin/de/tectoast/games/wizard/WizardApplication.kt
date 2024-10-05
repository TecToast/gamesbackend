package de.tectoast.games.wizard

import de.tectoast.games.UserSession
import de.tectoast.games.config
import de.tectoast.games.nameCache
import de.tectoast.games.sessionOrNull
import de.tectoast.games.wizard.model.GameData
import de.tectoast.games.wizard.model.WSMessage
import de.tectoast.games.wizard.model.WSMessage.*
import io.ktor.http.CacheControl
import io.ktor.serialization.deserialize
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import mu.KotlinLogging
import java.io.File

data class WizardSession(val token: String)

val logger = KotlinLogging.logger {}


fun Route.wizard() {

    staticFiles("/cardimages", File("cards/wizard"), index = null) {
        cacheControl {
            listOf(CacheControl.MaxAge(365 * 24 * 60 * 60))
        }
    }

    webSocket("/ws") {
        val session = call.sessionOrNull() ?: return@webSocket
        var username =
            if (config.devMode && session.userId == 0L) "TestUser" else nameCache.get<UserSession>(call, session)
        // TODO: Split between display name for FrontEnd and Username/ID for backend (e.g. WS connections in SocketManager)
        logger.info { "Username: $username" }
        lateinit var game: Game
        SocketManager.register(username, this)
        GameManager.updateOpenGames(this)
        for (frame in incoming) {
            try {
                val msg = converter?.deserialize<WSMessage>(frame)
                when (msg) {
                    null -> {}
                    is ChangeUsername -> {
                        if (config.devMode) {
                            username = msg.username
                            SocketManager.register(username, this)
                            sendWS(ChangeUsernameResponse(username))
                        }
                    }

                    is CreateGame -> {
                        val id = GameManager.generateGameId()
                        val g = Game(id, username)
                        GameManager.register(id, g)
                        sendWS(GameCreated(id))
                        GameManager.updateOpenGames(null)
                    }

                    is JoinGame -> {
                        game = gameOrRedirectHome(msg.gameID) ?: continue
                        if (game.phase == GamePhase.RUNNING) {
                            game.updateLobby()
                            game.sendCurrentState(username)
                        } else {
                            game.addPlayer(username)
                        }
                    }

                    else -> {
                        game.handleMessage(this, msg, username)
                    }
                }
            } catch (e: Exception) {
                logger.error(e.cause) { "Cause of Error in Websocket" }
                logger.error(e) { "Error in Websocket" }
            }
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
        val openGames =
            OpenGames(games.filter { it.value.phase == GamePhase.LOBBY }.map { GameData(it.value.owner, it.key) })
//        val openGames = OpenGames((1..5).map { GameData("TestUser$it", it) })
        socket?.sendWS(openGames) ?: SocketManager.broadcastToEveryConnectedClient(openGames)
    }

    fun findGame(id: Int) = games[id]

    fun register(id: Int, game: Game) {
        games[id] = game
    }

    fun generateGameId(): Int {
        var id = 0
        while (id in games) {
            id++
        }
        return id
    }

    suspend fun removeGame(id: Int) {
        games.remove(id)?.let {
            it.broadcast(RedirectHome)
            updateOpenGames(null)
        }
    }
}
