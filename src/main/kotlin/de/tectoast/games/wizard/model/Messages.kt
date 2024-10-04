package de.tectoast.games.wizard.model

import de.tectoast.games.wizard.Rules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WSMessage {

    @Serializable
    @SerialName("ChangeUsernameResponse")
    data class ChangeUsernameResponse(val username: String) : WSMessage()

    @Serializable
    @SerialName("CreateGame")
    data object CreateGame : WSMessage()

    @Serializable
    @SerialName("JoinGame")
    data class JoinGame(val gameID: Int) : WSMessage()

    @Serializable
    @SerialName("StartGame")
    data object StartGame : WSMessage()

    @Serializable
    @SerialName("LeaveGame")
    data class LeaveGame(val gameID: Int) : WSMessage()

    @Serializable
    @SerialName("ExitGame")
    data object ExitGame : WSMessage()

    @Serializable
    @SerialName("StitchGoal")
    data class StitchGoal(val name: String? = null, val goal: Int) : WSMessage()

    @Serializable
    @SerialName("LayCard")
    data class LayCard(val card: Card) : WSMessage()

    @Serializable
    @SerialName("RuleChangeRequest")
    data class RuleChangeRequest(val rule: Rules, val value: String) : WSMessage()

    @Serializable
    @SerialName("ChangeUsername")
    data class ChangeUsername(val username: String) : WSMessage()

    // ############################### RESPONSES ###############################
    @Serializable
    @SerialName("GameCreated")
    data class GameCreated(val gameID: Int) : WSMessage()

    @Serializable
    @SerialName("GameInfo")
    data class GameInfo(val players: Set<String>) : WSMessage()

    @Serializable
    @SerialName("RuleChange")
    data class RuleChange(val rules: Map<Rules, String>) : WSMessage()

    @Serializable
    @SerialName("EndGame")
    data class EndGame(val players: List<PlayerPoints>) : WSMessage()

    @Serializable
    @SerialName("Round")
    data class Round(val round: Int, val firstCome: String) : WSMessage()

    @Serializable
    @SerialName("Results")
    data class Results(val results: Map<String, Int>) : WSMessage()

    @Serializable
    @SerialName("Cards")
    data class Cards(val cards: List<Card>) : WSMessage()

    @Serializable
    @SerialName("PlayerCard")
    data class PlayerCard(val card: LayedCard) : WSMessage()

    @Serializable
    @SerialName("CurrentPlayer")
    data class CurrentPlayer(val player: String) : WSMessage()

    @Serializable
    @SerialName("Trump")
    data class Trump(val trump: Card, val shifted: Map<String, Int>) : WSMessage()

    @Serializable
    @SerialName("NewSubRound")
    data class NewSubRound(val winner: String) : WSMessage()

    @Serializable
    @SerialName("IsPredict")
    data class IsPredict(val isPredict: Boolean) : WSMessage()

    @Serializable
    @SerialName("UpdateDoneStitches")
    data class UpdateDoneStitches(val player: String, val amount: Int) : WSMessage()

    @Serializable
    @SerialName("GameStarted")
    data class GameStarted(val players: Set<String>) : WSMessage()

    @Serializable
    @SerialName("OpenGames")
    data class OpenGames(val games: List<GameData>) : WSMessage()

    @Serializable
    @SerialName("RedirectHome")
    data object RedirectHome : WSMessage()

    @Serializable
    @SerialName("HasPredicted")
    data class HasPredicted(val name: String) : WSMessage()

    @Serializable
    @SerialName("AcceptedGoal")
    data object AcceptedGoal : WSMessage()

}

@Serializable
data class GameData(val owner: String, val id: Int)

@Serializable
data class PlayerPoints(val player: String, val points: Int)
