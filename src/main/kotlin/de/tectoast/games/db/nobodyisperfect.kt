package de.tectoast.games.db

import kotlinx.serialization.Serializable

@Serializable
data class NobodyIsPerfectDataDB(
    val questions: List<NIPQuestion>
) : BackendBase()

@Serializable
data class NobodyIsPerfectDataFrontend(
    val questions: List<NIPQuestion>,
    val host: String
) : FrontEndBase<NobodyIsPerfectUser>()

@Serializable
data class NIPQuestion(
    val question: NIPQData,
    val answer: NIPQData
)

@Serializable
data class NIPQData(val title: String, val file: String? = null, val audio: String? = null)

@Serializable
data class NobodyIsPerfectUser(
    val avatarUrl: String, val displayName: String, val points: Int
)

@Serializable
data class PerfectAnswers(val host: Long, val gameID: String, val uid: Long, val questionIndex: Int, val answer: String)
