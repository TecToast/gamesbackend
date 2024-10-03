package de.tectoast.games.musicquiz

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import de.tectoast.games.createDefaultRoutes
import de.tectoast.games.db
import de.tectoast.games.db.MusicQuizDataDB
import de.tectoast.games.db.MusicQuizDataFrontend
import de.tectoast.games.db.MusicQuizUser
import de.tectoast.games.discord.jda
import de.tectoast.games.musicquiz.MusicQuizWSMessage.*
import de.tectoast.games.sessionOrUnauthorized
import de.tectoast.games.utils.GUILD_ID
import de.tectoast.games.utils.createDataCache
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.interactions.components.Modal
import dev.minn.jda.ktx.messages.reply_
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.litote.kmongo.eq
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.get
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val dataCache =
    createDataCache { MusicQuizUser(it.avatarUrl, it.effectiveName, 0, "..."/*.repeat(1000)*/) }

private val scope = CoroutineScope(Dispatchers.Default)

fun Route.musicQuiz() {
    createDefaultRoutes(db.musicquiz,
        dataCache,
        updateMap = mapOf(MusicQuizDataDB::tracks to MusicQuizDataFrontend::tracks),
        frontEndMapper = { MusicQuizDataFrontend(it.tracks) },
        backendSupplier = { MusicQuizDataDB(emptyList()) })
    webSocket("/ws/{id}") {
        val id = call.parameters["id"] ?: return@webSocket
        val session = call.sessionOrUnauthorized() ?: return@webSocket
        val data = db.musicquiz.findOne(MusicQuizDataDB::id eq id) ?: return@webSocket close()
        val store = botStore.getOrPut(id) {
            val manager = DefaultAudioPlayerManager()
            manager.registerSourceManager(YoutubeAudioSourceManager(true))
            val player = manager.createPlayer()
            MusicQuizStore(manager = manager, player = player, userList = data.participants)
        }.apply { hostSession = this@webSocket }
        while (true) {
            val msg = runCatching { receiveDeserialized<MusicQuizWSMessage>() }.onFailure {
                if (it is ClosedReceiveChannelException) {
                    return@webSocket
                }
            }.getOrNull()
            when (msg) {
                null -> {}
                is Join -> {
                    val guild = jda.getGuildById(GUILD_ID)!!
                    val member = guild.retrieveMember(UserSnowflake.fromId(session.userId))
                        .await()
                    member.voiceState?.channel?.let { channel ->
                        guild.audioManager.openAudioConnection(channel)
                    }
                    guildToQuiz[guild.idLong] = id
                    store.hostSession = this
                    sendWS(AllGuesses(store.userGuesses))
                    guild.audioManager.sendingHandler = object : AudioSendHandler {
                        private val buffer: ByteBuffer = ByteBuffer.allocate(1024)
                        private val frame: MutableAudioFrame = MutableAudioFrame()

                        init {
                            frame.setBuffer(buffer)
                        }

                        override fun canProvide(): Boolean {
                            // returns true if audio was provided
                            return store.player.provide(frame)
                        }

                        override fun provide20MsAudio(): ByteBuffer {
                            // flip to make it a read buffer
                            buffer.flip()
                            return buffer
                        }

                        override fun isOpus(): Boolean {
                            return true
                        }
                    }
                }

                is Pause -> {
                    if (msg.soft) {
                        if (!store.softPauseInProgress) {
                            store.softPauseInProgress = true
                            scope.launch {
                                for (i in 100 downTo 0 step 1) {
                                    store.player.volume = i
                                    delay(50)
                                }
                                store.player.isPaused = true
                                store.softPauseInProgress = false
                            }
                        }
                    } else {
                        store.player.isPaused = true
                    }
                }

                is Resume -> {
                    store.player.isPaused = false
                }

                is Restart -> {
                    store.lastActualUrl?.let { url ->
                        store.play(url, false)
                    }
                }

                is Play -> {
                    store.play(msg.yt, msg.clear)
                }

                else -> {}
            }
        }
    }
    jda.listener<ButtonInteractionEvent> { e ->
        when (e.componentId) {
            "guess" -> {
                val id = guildToQuiz[e.guild?.idLong]
                val data = db.musicquiz.findOne(MusicQuizDataDB::id eq id)
                    ?: return@listener e.reply_("Currently, no game is active.", ephemeral = true).queue()
                botStore[id] ?: return@listener e.reply_("Currently, no game is active.", ephemeral = true).queue()
                val uid = e.user.id
                if (uid !in data.participants) return@listener e.reply_(
                    "Du spielst gerade nicht mit!",
                    ephemeral = true
                )
                    .queue()
                e.replyModal(Modal("guess", "Guess") {
                    short("game", "Game", required = true)
                    short("track", "Track", required = true)
                }).queue()
            }
        }
    }
    jda.listener<ModalInteractionEvent> { e ->
        if (e.modalId == "guess") {
            val u = e.user.id
            val id = guildToQuiz[e.guild?.idLong]
            val store = botStore[id] ?: return@listener e.reply_("Currently, no game is active.", ephemeral = true).queue()
            val s = e.getValue("track")!!.asString + "\nSpiel: " + e.getValue("game")!!.asString
            store.userGuesses[u] = s
            store.hostSession.sendWS(Guess(u, s))
            e.reply_("Guess set!", ephemeral = true).queue()
        }
    }
}

data class MusicQuizStore(
    val userList: List<String> = mutableListOf(),
    val userGuesses: MutableMap<String, String> = mutableMapOf(),
    var lastActualUrl: String? = null,
    val manager: AudioPlayerManager,
    val player: AudioPlayer,
    var softPauseInProgress: Boolean = false,
) {
    lateinit var hostSession: WebSocketServerSession
    suspend fun play(url: String, clear: Boolean) {
        manager.playTrack(url, player)
        if (clear) {
            lastActualUrl = url
            userList.forEach {
                userGuesses[it] = "..."
            }
        }
    }
}

private val guildToQuiz = ConcurrentHashMap<Long, String>()
private val botStore = ConcurrentHashMap<String, MusicQuizStore>()

private val musicQuizLogger = KotlinLogging.logger {}

// lavaplayer isn't super kotlin-friendly, so we'll make it nicer to work with
suspend fun AudioPlayerManager.playTrack(query: String, player: AudioPlayer): AudioTrack {
    val track = suspendCoroutine<AudioTrack> {
        this.loadItem(query, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                it.resume(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                it.resume(playlist.tracks.first())
            }

            override fun noMatches() {
                musicQuizLogger.error("DU DUMMER HU")
            }

            override fun loadFailed(exception: FriendlyException?) {
                musicQuizLogger.error("Failed to load track", exception)
            }
        })
    }

    musicQuizLogger.debug("playing...")
    player.playTrack(track)
    player.volume = 100
    player.isPaused = false
    return track
}

fun WebSocketServerSession.sendWS(msg: MusicQuizWSMessage) = scope.launch {
    sendSerialized(msg)
}


@Serializable
sealed class MusicQuizWSMessage {
    @Serializable
    @SerialName("Pause")
    data class Pause(val soft: Boolean) : MusicQuizWSMessage()

    @Serializable
    @SerialName("Resume")
    data object Resume : MusicQuizWSMessage()

    @Serializable
    @SerialName("Restart")
    data object Restart : MusicQuizWSMessage()

    @Serializable
    @SerialName("Play")
    data class Play(val yt: String, val clear: Boolean = false) : MusicQuizWSMessage()

    @Serializable
    @SerialName("Join")
    data object Join : MusicQuizWSMessage()

    /////////////////////////////////////////////

    @Serializable
    @SerialName("Guess")
    data class Guess(val user: String, val guess: String) : MusicQuizWSMessage()

    @Serializable
    @SerialName("AllGuesses")
    data class AllGuesses(val guesses: Map<String, String>) : MusicQuizWSMessage()
}
