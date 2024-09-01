package de.tectoast.games.db

import kotlinx.serialization.Serializable

@Serializable
data class MusicQuizDataDB(
    val tracks: List<Track>
) : BackendBase()

@Serializable
data class MusicQuizDataFrontend(
    val tracks: List<Track>
) : FrontEndBase<MusicQuizUser>()

@Serializable
data class Track(
    val name: String,
    val game: String,
    val type: String,
    val url: String,
    val region: String
)

@Serializable
data class MusicQuizUser(
    val avatarUrl: String, val displayName: String, val points: Int, val guess: String
)
