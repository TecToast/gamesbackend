package de.tectoast.games.db

import de.tectoast.games.utils.ExpiringMap
import kotlinx.serialization.Serializable

@Serializable
sealed class FrontEndBase<T> {
    val participants: MutableMap<String, T> = mutableMapOf()
    val participantsList: MutableList<String> = mutableListOf()
    suspend inline fun store(
        dataCache: ExpiringMap<String, T>,
        participantsList: List<String>,
        onEach: (T) -> Unit = {}
    ) {
        this.participantsList.clear()
        this.participantsList.addAll(participantsList)
        this.participants.clear()
        this.participants.putAll(
            dataCache.getAll(participantsList).onEach { (_, u) -> onEach(u) },
        )
    }
}

@Serializable
sealed class BackendBase {
    var user: Long = -1
    var id: String = ""
    val participants: List<String> = mutableListOf()
}
