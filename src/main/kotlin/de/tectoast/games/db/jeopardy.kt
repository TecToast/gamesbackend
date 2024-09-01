package de.tectoast.games.db

import kotlinx.serialization.Serializable



@Serializable
data class JeopardyDataFrontend(
    val categories: Map<String, Map<String, JeopardyQuestion>>,
    val jokers: List<String>,
    val host: String
) : FrontEndBase<JeopardyUser>()

@Serializable
data class JeopardyDataDB(
    val categories: Map<String, Map<String, JeopardyQuestion>>,
    val jokers: List<String>,
) : BackendBase()


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
