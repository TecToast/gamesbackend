package de.tectoast.games.db

import kotlinx.serialization.Serializable

@Serializable
data class NobodyIsPerfectDataDB(
    val questions: List<NIPQuestion>
) : BackendBase()

@Serializable
data class NobodyIsPerfectDataFrontend(
    val questions: List<NIPQuestion>
) : FrontEndBase<NobodyIsPerfectUser>()

@Serializable
data class NIPQuestion(
    val name: String,
    val game: String,
    val type: String,
    val url: String,
    val region: String
)

@Serializable
data class NobodyIsPerfectUser(
    val avatarUrl: String, val displayName: String, val points: Int
)
