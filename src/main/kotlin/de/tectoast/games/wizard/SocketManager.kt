package de.tectoast.games.wizard

import de.tectoast.games.wizard.model.WSMessage
import io.ktor.server.websocket.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object SocketManager {
    private val sockets = mutableMapOf<String, WebSocketServerSession>()

    fun register(user: String, socket: WebSocketServerSession) {
        sockets[user] = socket
    }

    suspend fun send(user: String, message: WSMessage) {
        sockets[user]?.sendWS(message)
    }

    operator fun get(user: String) = sockets[user]!!

    suspend fun broadcastToEveryConnectedClient(message: WSMessage) {
        coroutineScope {
            for (socket in sockets.values) {
                launch {
                    socket.sendWS(message)
                }
            }
        }
    }

}

suspend fun String.send(message: WSMessage) {
    SocketManager.send(this, message)
}
suspend fun WebSocketServerSession.sendWS(message: WSMessage) {
    sendSerialized(message)
}
