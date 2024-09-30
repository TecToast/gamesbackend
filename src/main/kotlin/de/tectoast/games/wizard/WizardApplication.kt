package de.tectoast.games.wizard

import de.tectoast.games.UserSession
import de.tectoast.games.config
import de.tectoast.games.nameCache
import de.tectoast.games.sessionOrNull
import de.tectoast.games.wizard.model.GameData
import de.tectoast.games.wizard.model.WSMessage
import de.tectoast.games.wizard.model.WSMessage.*
import io.ktor.serialization.deserialize
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import mu.KotlinLogging
import java.io.File

data class WizardSession(val token: String)

val logger = KotlinLogging.logger {}


fun Route.wizard() {

    webSocket("/echo") {
        for (frame in incoming) {
            frame as? Frame.Text ?: continue
            send(frame.readText())
        }
    }

    staticFiles("/cardimages", File("cards/wizard"), index = null)

    webSocket("/ws") {
        try {
            suspend fun error() = sendWS(
                WSLoginResponse(null)
            )

            val session = call.sessionOrNull() ?: return@webSocket error()

            val username =
                if (config.devMode && session.userId == 0L) "TestUser" else nameCache.get<UserSession>(call, session)
            // TODO: Split between display name for FrontEnd and Username/ID for backend (e.g. WS connections in SocketManager)
            logger.info { "Username: $username" }

            lateinit var game: Game
            SocketManager.register(username, this)
            sendWS(WSLoginResponse(username))
            GameManager.updateOpenGames(this)
            for (frame in incoming) {
                val msg = converter?.deserialize<WSMessage>(frame)
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

private suspend fun WebSocketServerSession.gameOrRedirectHome(gameID: Int): Game? {
    return GameManager.findGame(gameID) ?: run {
        sendWS(RedirectHome)
        null
    }
}

object GameManager {
    private val games = mutableMapOf<Int, Game>()

    suspend fun updateOpenGames(socket: WebSocketServerSession?) {
//        val openGames = OpenGames(games.filter { !it.value.isRunning }.map { GameData(it.value.owner, it.key) })
        val openGames = OpenGames((1..5).map { GameData("TestUser$it", it) })
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
