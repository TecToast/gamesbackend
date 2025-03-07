package de.tectoast.games.nobodyisperfect

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import de.tectoast.games.*
import de.tectoast.games.db.NobodyIsPerfectDataDB
import de.tectoast.games.db.NobodyIsPerfectDataFrontend
import de.tectoast.games.db.NobodyIsPerfectUser
import de.tectoast.games.db.PerfectAnswers
import de.tectoast.games.discord.jda
import de.tectoast.games.jeopardy.fileNotAllowedRegex
import de.tectoast.games.jeopardy.idRegex
import de.tectoast.games.musicquiz.playTrack
import de.tectoast.games.nobodyisperfect.NobodyIsPerfectWSMessage.*
import de.tectoast.games.utils.GUILD_ID
import de.tectoast.games.utils.createDataCache
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.messages.reply_
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import org.litote.kmongo.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap


private val dataCache =
    createDataCache { NobodyIsPerfectUser(it.avatarUrl, it.effectiveName, 0) }

private val scope = CoroutineScope(Dispatchers.Default)

lateinit var mediaBaseDir: File

fun Route.nobodyIsPerfect() {
    createDefaultRoutes(
        db.nobodyIsPerfect,
        dataCache,
        updateMap = mapOf(NobodyIsPerfectDataDB::questions to NobodyIsPerfectDataFrontend::questions),
        frontEndMapper = { NobodyIsPerfectDataFrontend(it.questions, it.user.toString()) },
        backendSupplier = { NobodyIsPerfectDataDB(emptyList()) })
    post("/upload") {
        val session = call.sessionOrUnauthorized() ?: return@post
        val data = call.receiveMultipart()
        val id = data.readString("id") ?: return@post call.badReq()
        val category = data.readString("num") ?: return@post call.badReq()
        val type = data.readString("type")?.takeIf { it == "Question" || it == "Answer" } ?: return@post call.badReq()
        setOf(id, category).forEach {
            idRegex.matchEntire(it) ?: return@post call.badReq()
        }
        val path =
            mediaBaseDir.resolve(session.userId.toString()).resolve(id).resolve(category).resolve(type)
        path.mkdirs()
        val fileData = (data.readPart() as? PartData.FileItem) ?: return@post call.badReq()
        val file = fileData.provider().readRemaining().readByteArray()
        val resolve = path.resolve(fileData.originalFileName?.replace(fileNotAllowedRegex, "") ?: "file")
        resolve.writeBytes(file)
        call.respond(HttpStatusCode.OK, resolve.name)
    }
    staticFiles("/media", mediaBaseDir)
    webSocket("/ws/{id}") {
        val id = call.parameters["id"] ?: return@webSocket
        val session = call.sessionOrUnauthorized() ?: return@webSocket
        val data =
            db.nobodyIsPerfect.findOne(NobodyIsPerfectDataDB::user eq session.userId, NobodyIsPerfectDataDB::id eq id)
                ?: return@webSocket close()
        val store = botStore.getOrPut(id) {
            val manager = DefaultAudioPlayerManager()
            manager.registerSourceManager(YoutubeAudioSourceManager(true))
            val player = manager.createPlayer()
            NobodyIsPerfectStore(manager = manager, player = player, userList = data.participants)
        }.apply { hostSession = this@webSocket }
        while (true) {
            val msg = runCatching { receiveDeserialized<NobodyIsPerfectWSMessage>() }.onFailure {
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
                        try {
                            guild.audioManager.openAudioConnection(channel)
                        } catch (_: InsufficientPermissionException) {
                        }
                    }
                    guildToQuiz[guild.idLong] = id
                    store.hostSession = this
                    sendWS(AllAnswers(store.userAnswers))
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

                is StopTrack -> {
                    store.player.isPaused = true
                }

                is AcceptAnswers -> {
                    store.acceptingAnswers = msg.state
                    if (msg.saveIndex >= 0) {
                        store.userAnswers.forEach { (uid, answer) ->
                            db.perfectAnswers.updateOne(
                                and(
                                    PerfectAnswers::host eq session.userId,
                                    PerfectAnswers::gameID eq id,
                                    PerfectAnswers::uid eq uid.toLong(),
                                    PerfectAnswers::questionIndex eq msg.saveIndex
                                ), set(PerfectAnswers::answer setTo answer), upsert()
                            )
                        }
                    }
                    if (msg.deleteAnswers) {
                        store.userAnswers.clear()
                    }
                }

                is PlayTrackOfQuestion -> {
                    data.questions.getOrNull(msg.questionIndex)?.let {
                        store.play(it.question.audio ?: return@let)
                    }
                }

                is PlayTrackOfAnswer -> {
                    data.questions.getOrNull(msg.questionIndex)?.let {
                        store.play(it.answer.audio ?: return@let)
                    }
                }

                is PlayYT -> {
                    store.play(msg.yt)
                }

                else -> {}
            }
        }
    }
    suspend fun handleEvent(gid: Long, uid: String, answer: String, reply: (String) -> Unit) {
        val id = guildToQuiz[gid]
        val data = db.nobodyIsPerfect.findOne(NobodyIsPerfectDataDB::id eq id)
            ?: return reply("Currently, no game is active.")
        val store = botStore[id] ?: return reply("Currently, no game is active.")
        if (uid !in data.participants) return reply(
            "Du spielst gerade nicht mit!",
        )
        if (!store.acceptingAnswers) return reply("Es können derzeit keine Antworten eingereicht werden!")
        store.userAnswers[uid] = answer
        store.hostSession.sendWS(Answer(uid, answer))
        reply(answer)
    }

    jda.listener<SlashCommandInteractionEvent> { e ->
        if (e.name != "nobodyisperfect") return@listener
        val answer =
            e.getOption("answer")?.asString ?: return@listener e.reply_("Bitte gib eine Antwort an!", ephemeral = true)
                .queue()
        handleEvent(e.guild!!.idLong, e.user.id, answer) {
            e.reply_(it, ephemeral = true).queue()
        }
    }
    jda.listener<CommandAutoCompleteInteractionEvent> { e ->
        if (e.name != "nobodyisperfect") return@listener
        handleEvent(e.guild!!.idLong, e.user.id, e.focusedOption.value) {
            if (it.length <= 100)
                e.replyChoiceStrings(it.ifBlank { "..." }).queue()
        }
    }
}


data class NobodyIsPerfectStore(
    val userList: List<String> = mutableListOf(),
    val userAnswers: MutableMap<String, String> = mutableMapOf(),
    var acceptingAnswers: Boolean = true,
    val manager: AudioPlayerManager,
    val player: AudioPlayer,
) {
    lateinit var hostSession: WebSocketServerSession
    suspend fun play(url: String) {
        manager.playTrack(url, player)
    }
}

private val guildToQuiz = ConcurrentHashMap<Long, String>()
private val botStore = ConcurrentHashMap<String, NobodyIsPerfectStore>()

private fun WebSocketServerSession.sendWS(msg: NobodyIsPerfectWSMessage) = scope.launch {
    sendSerialized(msg)
}

@Serializable
sealed class NobodyIsPerfectWSMessage {

    @Serializable
    @SerialName("AcceptAnswers")
    data class AcceptAnswers(val state: Boolean, val deleteAnswers: Boolean = false, val saveIndex: Int = -1) :
        NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("PlayTrackOfQuestion")
    data class PlayTrackOfQuestion(val questionIndex: Int) : NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("PlayTrackOfAnswer")
    data class PlayTrackOfAnswer(val questionIndex: Int) : NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("PlayYT")
    data class PlayYT(val yt: String) : NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("StopTrack")
    data object StopTrack : NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("Join")
    data object Join : NobodyIsPerfectWSMessage()

    /////////////////////////////
    @Serializable
    @SerialName("Answer")
    data class Answer(val user: String, val answer: String) : NobodyIsPerfectWSMessage()

    @Serializable
    @SerialName("AllAnswers")
    data class AllAnswers(val answers: Map<String, String>) : NobodyIsPerfectWSMessage()
}
