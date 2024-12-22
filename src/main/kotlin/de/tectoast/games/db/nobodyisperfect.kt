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
    val question: NIPQData,
    val answer: NIPQData
)

@Serializable
data class NIPQData(val title: String, val image: String? = null, val video: String? = null, val audio: String? = null)

@Serializable
data class NobodyIsPerfectUser(
    val avatarUrl: String, val displayName: String, val points: Int
)
