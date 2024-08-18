package de.tectoast.games.jeopardy

import de.tectoast.games.db
import de.tectoast.games.discord.jda
import de.tectoast.games.sessionOrUnauthorized
import de.tectoast.games.utils.ExpiringMap
import dev.minn.jda.ktx.coroutines.await
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.litote.kmongo.and
import org.litote.kmongo.eq
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import java.io.File
import kotlin.time.Duration.Companion.days

private val dataCache = ExpiringMap(1.days) { list ->
    jda.getGuildById(1036657324909146153)!!.findMembers { mem -> mem.id in list }.await().sortedBy { list.indexOf(it.id) }.map {
        JeopardyUser(
            it.user.effectiveAvatarUrl.substringBeforeLast(".") + ".png?size=512", it.user.effectiveName, null, 0
        )
    }
}
lateinit var mediaBaseDir: File
private val idRegex = Regex("[a-zA-Z0-9]+")
private val fileNotAllowedRegex = Regex("[^.a-zA-Z0-9]")

fun Route.jeopardy() {
    get("/data/{id}") {
        val data = call.findJeopardyData() ?: return@get
        call.respond(
            JeopardyDataFrontend(
                data.categories,
                data.jokers,
                dataCache.getAll(data.participants).onEach { (_, u) -> u.jokers = data.jokers },
                data.participants,
                data.user.toString()
            )
        )
    }
    get("/my") {
        val session = call.sessionOrUnauthorized() ?: return@get
        val data = db.jeopardy.find(JeopardyDataDB::user eq session.userId).toList()
        call.respond(data.map { it.id })
    }
    post("/create") {
        val session = call.sessionOrUnauthorized() ?: return@post
        val data = call.receiveText()
        call.respond(runCatching {
            db.jeopardy.insertOne(
                JeopardyDataDB(
                    session.userId, data, emptyMap(), listOf("R", "S"), emptyList()
                )
            )
        }.fold(onSuccess = { HttpStatusCode.Created }, onFailure = { HttpStatusCode.BadRequest })
        )

    }
    post("/update/{id}") {
        val data = call.findJeopardyData() ?: return@post
        val newData = call.receive<JeopardyDataFrontend>()
        call.respond(runCatching {
            db.jeopardy.updateOne(
                and(JeopardyDataDB::user eq data.user, JeopardyDataDB::id eq data.id), set(
                    JeopardyDataDB::categories setTo newData.categories,
                    JeopardyDataDB::jokers setTo newData.jokers,
                    JeopardyDataDB::participants setTo newData.participants.keys.toList()
                )
            )
        }.fold(onSuccess = { HttpStatusCode.OK }, onFailure = {
            it.printStackTrace()
            HttpStatusCode.BadRequest
        })
        )
    }
    post("/upload") {
        val session = call.sessionOrUnauthorized() ?: return@post
        val data = call.receiveMultipart()
        val id = data.readString("id") ?: return@post call.badReq()
        val category = data.readString("category") ?: return@post call.badReq()
        val points = data.readString("points") ?: return@post call.badReq()
        val type = data.readString("type")?.takeIf { it == "Question" || it == "Answer" } ?: return@post call.badReq()
        setOf(id, category, points).forEach {
            idRegex.matchEntire(it) ?: return@post call.badReq()
        }
        val path =
            mediaBaseDir.resolve(session.userId.toString()).resolve(id).resolve(category).resolve(points).resolve(type)
        path.mkdirs()
        val fileData = (data.readPart() as? PartData.FileItem) ?: return@post call.badReq()
        val file = fileData.streamProvider().readBytes()
        val resolve = path.resolve(fileData.originalFileName?.replace(fileNotAllowedRegex, "") ?: "file")
        resolve.writeBytes(file)
        call.respond(HttpStatusCode.OK, resolve.name)
    }
    staticFiles("/media", mediaBaseDir)

}

private suspend fun MultiPartData.readString(name: String): String? {
    val part = readPart() as? PartData.FormItem ?: return null
    if (part.name != name) return null
    return part.value.encodeURLPathPart()
}

suspend fun ApplicationCall.badReq() = respond(HttpStatusCode.BadRequest)
private suspend fun ApplicationCall.findJeopardyData(): JeopardyDataDB? {
    val session = sessionOrUnauthorized() ?: return null
    val data = db.jeopardy.findOne(
        JeopardyDataDB::user eq session.userId, JeopardyDataDB::id eq parameters["id"]
    )
    if (data == null) {
        respond(HttpStatusCode.NotFound, "No data for session and user found")
        return null
    }
    return data
}


@Serializable
data class JeopardyDataDB(
    val user: Long,
    val id: String,
    val categories: Map<String, Map<String, JeopardyQuestion>>,
    val jokers: List<String>,
    val participants: List<String>,
)

@Serializable
data class JeopardyDataFrontend(
    val categories: Map<String, Map<String, JeopardyQuestion>>,
    val jokers: List<String>,
    val participants: Map<String, JeopardyUser>,
    val participantList: List<String>,
    val host: String
)

@Serializable
data class JeopardyQuestion(
    val question: QAData,
    val answer: QAData,
)

@Serializable
data class QAData(
    val title: String, val image: String? = null
)

@Serializable
data class JeopardyUser(
    val avatarUrl: String, val displayName: String, var jokers: List<String>? = null, val points: Int
)
