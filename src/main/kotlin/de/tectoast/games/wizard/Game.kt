package de.tectoast.games.wizard

import de.tectoast.games.wizard.model.*
import de.tectoast.games.wizard.model.WSMessage.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.security.SecureRandom
import kotlin.math.abs

class Game(val id: Int, val owner: String) {
    val stitchGoals = mutableMapOf<String, Int>()
    val stitchDone = mutableMapOf<String, Int>()
    val points = mutableMapOf<String, Int>()
    val cards = mutableMapOf<String, MutableList<Card>>()
    val layedCards = mutableMapOf<String, Card>()
    var firstCard: Card? = null
    var trump: Card = NOTHINGCARD
    var players = mutableSetOf<String>()
    var phase = GamePhase.LOBBY
    var currentPlayer = "Ofl"
    var roundPlayers = mutableListOf<String>()
    var round = 0
    var isPredict = true
    var originalOrderForSubround = listOf<String>()
    val rules = mutableMapOf<Rules, String>().apply {
        Rules.entries.forEach {
            this[it] = it.options.first()
        }
    }
    val allCards by lazy {
        buildList {
            for (color in listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
                for (value in 1..13) {
                    add(Card(color, value.toFloat()))
                }
            }
            for (i in 1..4) {
                add(Card(Color.MAGICIAN, i.toFloat()))
                add(Card(Color.FOOL, i.toFloat()))
            }
            if (checkRule(Rules.SPECIALCARDS) == "Aktiviert") {
                add(BOMB)
                add(SEVENPOINTFIVE)
                add(NINEPOINTSEVENFIVE)
            }
        }
    }

    private fun checkRule(rule: Rules) = rules[rule] ?: rule.options.first()

    init {
        runBlocking {
            addPlayer(owner)
        }
    }

    suspend fun addPlayer(name: String) {
        players.add(name)
        updateLobby()
        name.send(RuleChange(rules))
    }

    suspend fun removePlayer(name: String) {
        if (players.remove(name)) {
            updateLobby()
        }
    }

    suspend fun broadcast(message: WSMessage) {
        for (user in players) {
            user.send(message)
        }
    }

    suspend inline fun broadcast(func: (String) -> WSMessage) {
        for (user in players) {
            user.send(func(user))
        }
    }

    suspend fun updateLobby() {
        broadcast(GameInfo(players))
    }

    suspend fun updateStitches(name: String) {
        broadcast(StitchGoal(name, stitchGoals[name]!!))
    }

    private fun generateOrder(modifier: Int): List<String> {
        return buildList {
            val list = players.toList()
            for (i in list.indices) {
                add(list[(i + round - 1 + modifier) % list.size])
            }
        }
    }

    fun generateStitchOrder() = generateOrder(0)
    fun generatePlayOrder() = generateOrder(1)

    suspend fun checkIfAllPredicted(name: String) {
        broadcast(HasPredicted(name))
        if (stitchGoals.size == players.size) {
            roundPlayers.clear()
            players.forEach {
                updateStitches(it)
            }
            nextPlayer()
        } else {
            name.send(AcceptedGoal)
        }
    }

    suspend fun endGame() =
        broadcast(EndGame(players.sortedByDescending { points[it]!! }.map { PlayerPoints(it, points[it]!!) }))

    suspend fun nextRound(nodelay: Boolean) {
        delay(if (nodelay) 0 else 5000)
        if (++round * players.size > allCards.size) {
            endGame()
            return
        }
        giveCards(round)
        roundPlayers = generateStitchOrder().toMutableList()
        broadcast(Round(round = round, firstCome = roundPlayers[1]))
        broadcast(IsPredict(true))
        originalOrderForSubround = roundPlayers.toList()
        if (checkRule(Rules.PREDICTION) == "Nacheinander") {
            nextPlayer()
        } else {
            broadcast {
                CurrentPlayer(it)
            }
        }
    }

    private fun addPoints(player: String, amount: Int) = points.add(player, amount)
    private fun <T> MutableMap<T, Int>.add(key: T, value: Int) = compute(key) { _, v -> (v ?: 0) + value }

    private val rnd = SecureRandom()

    //TODO for development and testing phase
    val forcedCards = listOf<Card>()

    suspend fun giveCards(round: Int) {
        val stack = allCards.shuffled(rnd) as MutableList<Card>
        cards.clear()
        forcedCards.forEach { stack.remove(it) }
        val mutableForced = forcedCards.toMutableList()
        repeat(round) {
            for (player in players) {
                cards.getOrPut(player) { mutableListOf() }
                    .add(mutableForced.removeFirstOrNull() ?: stack.removeFirstOrNull() ?: NOTHINGCARD)
            }
        }
        val shifted = mutableMapOf<String, Int>()
        trump = stack.removeFirstOrNull() ?: NOTHINGCARD
        if (checkRule(Rules.TRUMP) == "Nur Farben") {
            val forbidden = listOf(Color.MAGICIAN, Color.FOOL, Color.Special)
            while (trump.color in forbidden) {
                trump = stack.removeFirstOrNull() ?: NOTHINGCARD
                if (trump.color in forbidden) shifted.add(trump.color.text, 1)
            }
        }
        broadcast(Trump(trump, shifted))
        coroutineScope {
            for (player in players) {
                launch {
                    player.send(Cards(cards[player]!!))
                }
            }
        }
    }

    /*
    * winner ist hier nie null, sondern gibt immer den Spieler mit dem höchsten gelegten Kartenwert an
     */
    suspend fun newSubround(winner: String, wasBombUsed: Boolean) {
        layedCards.clear()
        firstCard = null
        val winnerMessage = NewSubRound(winner.takeUnless { wasBombUsed }, winner)
        if (cards[currentPlayer]!!.isEmpty()) {
            val results = buildMap {
                players.forEach { p ->
                    val amount =
                        (if (stitchDone[p] == stitchGoals[p]) 20 + stitchDone[p]!! * 10 else -10 * abs(stitchDone[p]!! - stitchGoals[p]!!)).let {
                            if (checkRule(Rules.POINTS) == "Max. 30") it.coerceAtMost(30) else it
                        }
                    addPoints(p, amount)
                    put(p, amount)
                }
            }
            stitchGoals.clear()
            stitchDone.clear()
            isPredict = true
            broadcast(Results(results))
            broadcast(winnerMessage)
            nextRound(false)
        } else {
            roundPlayers = players.toMutableList()
            while (roundPlayers.first() != winner) roundPlayers.add(roundPlayers.removeFirst())
            originalOrderForSubround = roundPlayers.toList()
            currentPlayer = roundPlayers.removeFirst()
            broadcast(winnerMessage)
        }
    }

    suspend fun nextPlayer() {
        currentPlayer = roundPlayers.removeFirstOrNull() ?: run {
            if (isPredict) {
                roundPlayers = generatePlayOrder().toMutableList()
                originalOrderForSubround = roundPlayers.toList()
                isPredict = false
                broadcast(IsPredict(false))
                return nextPlayer()
            }
            val firstPlayerOfRound = originalOrderForSubround[0]
            val winner = when {
                layedCards.values.all { it.color == Color.FOOL } -> firstPlayerOfRound
                layedCards.values.any { it.color == Color.MAGICIAN } -> {
                    if (checkRule(Rules.MAGICIAN) == "Letzter Zauberer") layedCards.entries.last { it.value.color == Color.MAGICIAN }.key
                    else layedCards.entries.first { it.value.color == Color.MAGICIAN }.key
                }

                else -> {
                    var highest = layedCards[firstPlayerOfRound]!! to firstPlayerOfRound
                    for (i in 1..<players.size) {
                        val playerToCheck = originalOrderForSubround[i]
                        val card = layedCards[playerToCheck]!!
                        if (card.isHigherThan(highest.first)) {
                            highest = card to playerToCheck
                        }
                    }
                    highest.second
                }
            }
            val wasBombUsed = layedCards.values.contains(BOMB)

            if (!wasBombUsed) {
                stitchDone.add(winner, 1)
                broadcast(UpdateDoneStitches(winner, stitchDone[winner]!!))
            }
            newSubround(winner, wasBombUsed)
            return
        }
        broadcast(CurrentPlayer(currentPlayer))
    }

    /**
     * @return true if the card is higher than the first card or trump
     */
    fun Card.isHigherThan(highestCardUntilNow: Card): Boolean {
        if (this.color == Color.FOOL) return false
        if (highestCardUntilNow == BOMB) return true
        if (highestCardUntilNow.color == Color.FOOL) return true
        if (this.color != highestCardUntilNow.color && this.color != trump.color) return false
        if (this.color == highestCardUntilNow.color) return this.value > highestCardUntilNow.value
        // we are trump and the other card isn't
        return true
    }

    suspend fun changeRule(rule: Rules, value: String) {
        rules[rule] = value
        broadcast(RuleChange(rules))
    }

    suspend fun layCard(name: String, layCard: LayCard) {
        if (name != currentPlayer || isPredict) return
        val playerCards = cards[name]!!
        val (realCard, selectedColor) = layCard
        if (realCard !in playerCards) return
        val card = selectedColor?.let {
            if (!it.isNormalColor || realCard !in rainbowCards) return
            realCard.copy(color = it)
        } ?: realCard
        firstCard?.let { fc ->
            if (card.color == Color.MAGICIAN || card.color == Color.FOOL || card in specialCards || fc.color == Color.MAGICIAN || fc == BOMB) return@let
            if (fc.color != card.color && playerCards.any { it.color == fc.color }) return
        }
        if (layedCards.values.all { it.color == Color.FOOL }) {
            firstCard = card
        }
        layedCards[name] = card
        playerCards.remove(realCard)
        broadcast(PlayerCard(LayedCard(card, name)))
        nextPlayer()
    }

    suspend fun start() {
        if (phase != GamePhase.LOBBY) return
        players = players.shuffled().toMutableSet()
        players.forEach { points[it] = 0 }
        phase = GamePhase.RUNNING
        broadcast(GameStarted(players))
        nextRound(true)
    }

    suspend fun sendCurrentState(username: String) {
        with(SocketManager[username]) {
            sendWS(Cards(cards[username].orEmpty()))
            sendWS(Trump(trump, emptyMap()))
            sendWS(Round(round, originalOrderForSubround[1].takeIf { stitchGoals[username] == null } ?: ""))
            sendWS(IsPredict(isPredict))
            stitchDone.forEach { (user, num) ->
                sendWS(UpdateDoneStitches(user, num))
            }
            sendWS(GameStarted(players))
            layedCards.entries.forEach {
                sendWS(PlayerCard(LayedCard(it.value, it.key)))
            }
            val isBlind = checkRule(Rules.PREDICTION) == "Blind"

            if (isBlind && isPredict) {
                stitchGoals.keys.forEach {
                    sendWS(HasPredicted(it))
                }
                sendWS(CurrentPlayer(username))
            } else {
                stitchGoals.forEach { (user, num) ->
                    sendWS(StitchGoal(user, num))
                }
                sendWS(CurrentPlayer(currentPlayer))
            }
        }
    }

    suspend fun handleMessage(socket: WebSocketServerSession, msg: WSMessage, username: String) {
        when (msg) {
            is StartGame -> {
                if (username == owner) {
                    start()
                }
            }

            is LeaveGame -> {
                // TODO: Check which phase and probably change owner etc (or delete the game)
                when (phase) {
                    GamePhase.LOBBY -> {
                        if (username == owner) {
                            GameManager.removeGame(id)
                        }
                        removePlayer(username)
                        socket.sendWS(RedirectHome)
                    }

                    GamePhase.RUNNING -> {
                        endGame()
                        phase = GamePhase.FINISHED
                    }

                    GamePhase.FINISHED -> {
                        GameManager.removeGame(id)
                        socket.sendWS(RedirectHome)
                    }
                }
            }

            is StitchGoal -> {
                if (!isPredict) return
                val default = checkRule(Rules.PREDICTION) == "Nacheinander"
                if (default && username != currentPlayer) return
                stitchGoals[username] = msg.goal
                stitchDone[username] = 0
                if (default) {
                    updateStitches(username)
                    nextPlayer()
                } else {
                    checkIfAllPredicted(username)
                }
            }

            is RuleChangeRequest -> {
                if (username == owner && phase == GamePhase.LOBBY) changeRule(msg.rule, msg.value)
            }

            is LayCard -> {
                layCard(username, msg)
            }

            else -> {
                logger.warn { "Unknown message: $msg" }
            }
        }
    }

    companion object {
        val NOTHINGCARD = Card(Color.NOTHING, -1f)
        val BOMB = Card(Color.Special, 1f)
        val SEVENPOINTFIVE = Card(Color.Special, 7.5f)
        val NINEPOINTSEVENFIVE = Card(Color.Special, 9.75f)
        val logger = KotlinLogging.logger {}
        val specialCards = setOf(BOMB, SEVENPOINTFIVE, NINEPOINTSEVENFIVE)
        val rainbowCards = setOf(SEVENPOINTFIVE, NINEPOINTSEVENFIVE)
    }

}

@Serializable
enum class Rules(val options: List<String>) {
    @SerialName("Punkte")
    POINTS(listOf("Normal", "Max. 30")),

    @SerialName("Zauberer")
    MAGICIAN(
        listOf(
            "Normal", "Letzter Zauberer"
        )
    ),

    @SerialName("Ansage")
    PREDICTION(listOf("Nacheinander", "Blind")),

    @SerialName("Trumpf")
    TRUMP(listOf("Normal", "Nur Farben")),

    @SerialName("Spezialkarten")
    SPECIALCARDS(listOf("Aktiviert", "Deaktiviert")),
}

enum class GamePhase {
    LOBBY, RUNNING, FINISHED
}
