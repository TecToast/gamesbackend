package de.tectoast.games

import de.tectoast.games.db.JeopardyDataDB
import de.tectoast.games.db.MusicQuizDataDB
import de.tectoast.games.db.NobodyIsPerfectDataDB
import de.tectoast.games.db.PerfectAnswers
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.serialization.configuration

val db: MongoGames get() = delegateDb ?: error("MongoDB not initialized!")

private var delegateDb: MongoGames? = null

fun initMongo(config: MongoConfig) {
    delegateDb?.let { error("MongoDB already initialized!") }
    delegateDb = MongoGames(config.url, config.dbName)
}

class MongoGames(dbUrl: String, dbName: String) {
    val db = run {
        configuration = configuration.copy(classDiscriminator = "type", encodeDefaults = false)
        KMongo.createClient(dbUrl).coroutine.getDatabase(dbName)
    }

    val jeopardy by lazy { db.getCollection<JeopardyDataDB>("jeopardy") }
    val musicquiz by lazy { db.getCollection<MusicQuizDataDB>("musicquiz") }
    val nobodyIsPerfect by lazy { db.getCollection<NobodyIsPerfectDataDB>("nobodyisperfect") }
    val perfectAnswers by lazy { db.getCollection<PerfectAnswers>("perfectanswers") }
}
