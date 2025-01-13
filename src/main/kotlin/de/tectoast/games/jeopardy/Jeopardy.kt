package de.tectoast.games.jeopardy

import de.tectoast.games.*
import de.tectoast.games.db.JeopardyDataDB
import de.tectoast.games.db.JeopardyDataFrontend
import de.tectoast.games.db.JeopardyUser
import de.tectoast.games.utils.createDataCache
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import java.io.File

private val dataCache = createDataCache { JeopardyUser(it.avatarUrl, it.effectiveName, null, 0) }
lateinit var mediaBaseDir: File
val idRegex = Regex("[a-zA-Z0-9]+")
val fileNotAllowedRegex = Regex("[^.a-zA-Z0-9]")

fun Route.jeopardy() {
    createDefaultRoutes(
        db.jeopardy,
        dataCache,
        updateMap = mapOf(
            JeopardyDataDB::categories to JeopardyDataFrontend::categories,
            JeopardyDataDB::jokers to JeopardyDataFrontend::jokers
        ),
        frontEndMapper = { JeopardyDataFrontend(it.categories, it.jokers, it.user.toString()) },
        onEachUser = { u -> u.jokers = jokers }) {
        JeopardyDataDB(
            emptyMap(), listOf("R", "S")
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
        val file = fileData.provider().readRemaining().readByteArray()
        val resolve = path.resolve(fileData.originalFileName?.replace(fileNotAllowedRegex, "") ?: "file")
        resolve.writeBytes(file)
        call.respond(HttpStatusCode.OK, resolve.name)
    }
    staticFiles("/media", mediaBaseDir)

}
