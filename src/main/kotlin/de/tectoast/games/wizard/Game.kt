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
    val specialRoles = mutableMapOf<SpecialRole, String>()
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
                    add(Card(color, value))
                }
            }
            for (i in 1..4) {
                add(Card(Color.MAGICIAN, i))
                add(Card(Color.FOOL, i))
            }
            //Spezialkarten haben Color.Special
            //Bombe hat value 1
            //weitere Spezialkarten haben andere value-Werte
            if (checkRule(Rules.SPECIALCARDS) == "Aktiviert") {
                add(BOMB)
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
    //for development and testing phase
    val forcedCards = listOf<Card>()

    suspend fun giveCards(round: Int) {
        val stack = allCards.shuffled(rnd) as MutableList<Card>
        cards.clear()
        forcedCards.forEach { stack.remove(it) }
        val mutableForced = forcedCards.toMutableList()

        if (FunctionalSpecialRole.HEADFOOL in specialRoles.keys) {
            val firstFool = stack.first { it.color == Color.FOOL }
            stack.remove(firstFool)
            cards.getOrPut(specialRoles[FunctionalSpecialRole.HEADFOOL].orEmpty()) { mutableListOf() }.add(firstFool)
        }

        if (FunctionalSpecialRole.SERVANT in specialRoles.keys) {
            for (servantCard in stack.filter { it.value == 2 && it.color in setOf(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE) }) {
                if (cards.getOrPut(specialRoles[FunctionalSpecialRole.SERVANT]!!) { mutableListOf() }.size < round) {
                    stack.remove(servantCard)
                    cards.getValue(specialRoles[FunctionalSpecialRole.SERVANT]!!).add(servantCard)
                } else break
            }
        }

        var enoughCardsDealt = false
        while (!enoughCardsDealt) {
            enoughCardsDealt = true
            for (player in players) {
                if (cards.getOrPut(player) { mutableListOf() }.size < round) {
                    enoughCardsDealt = false
                    val nextCard = mutableForced.removeFirstOrNull() ?: stack.removeFirstOrNull() ?: NOTHINGCARD

                    var cardStolen = false
                    for (role in specialRoles.keys) {
                        if (role is ColorPreferenceSpecialRole &&
                            role.color == nextCard.color &&
                            rnd.nextInt(role.chance) == 0 &&
                            cards.getOrPut(specialRoles[role]!!) { mutableListOf() }.size < round) {

                            cards.getValue(specialRoles[role]!!).add(nextCard)
                            cardStolen = true
                            break
                        }
                    }
                    if (nextCard == BOMB && FunctionalSpecialRole.BLASTER in specialRoles.keys &&
                        cards.getOrPut(specialRoles[FunctionalSpecialRole.BLASTER]!!) { mutableListOf() }.size < round) {
                        cards.getValue(specialRoles[FunctionalSpecialRole.BLASTER]!!).add(nextCard)
                        cardStolen = true
                    }

                    if (!cardStolen) cards.getOrPut(player) { mutableListOf() }.add(nextCard)
                }
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
            var numberOfLoosingPlayers = 0
            val results = mutableMapOf<String, Int>()
            players.forEach { p ->
                val amount =
                    if (specialRoles[FunctionalSpecialRole.PESSIMIST] == p) {
                        (if ((stitchDone[p] == stitchGoals[p]) && stitchDone[p] == 0) 50 else
                                (if (stitchDone[p] == stitchGoals[p]) 20 + stitchDone[p]!! * 10 else -10 * abs(stitchDone[p]!! - stitchGoals[p]!!)).coerceAtMost(70))
                    } else if (specialRoles[FunctionalSpecialRole.OPTIMIST] == p) {
                        if (stitchDone[p] == stitchGoals[p]) {
                            (if (stitchDone[p]!! <= 3) stitchDone[p]!! * 10 else 20 + stitchDone[p]!! * 10)
                        } else if (abs(stitchDone[p]!! - stitchGoals[p]!!) == 1) {
                            5 * stitchDone[p]!!
                        } else {
                            -10 * abs(stitchDone[p]!! - stitchGoals[p]!!)
                        }
                    } else {
                        (if (stitchDone[p] == stitchGoals[p]) 20 + stitchDone[p]!! * 10 else -10 * abs(stitchDone[p]!! - stitchGoals[p]!!))
                    }

                if (amount < 0 && specialRoles[FunctionalSpecialRole.GLEEFUL] != p) {
                    numberOfLoosingPlayers += 1
                }
                results[p] = amount
            }
            if (FunctionalSpecialRole.GLEEFUL in specialRoles.keys) {
                results[specialRoles[FunctionalSpecialRole.GLEEFUL]!!] = results[specialRoles[FunctionalSpecialRole.GLEEFUL]!!]!! + numberOfLoosingPlayers * 3
            }

            for (p in results.keys) {
                if (checkRule(Rules.POINTS) == "Max. 30") results[p] = results[p]!!.coerceAtMost(30)
                addPoints(p, results[p]!!)
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
            val winner =
                when {
                    layedCards.values.all { it.color == Color.FOOL } -> firstPlayerOfRound
                    layedCards.values.any { it.color == Color.MAGICIAN } -> {
                        if (checkRule(Rules.MAGICIAN) == "Letzter Zauberer") layedCards.entries.last { it.value.color == Color.MAGICIAN }.key
                        else if (checkRule(Rules.MAGICIAN) == "Mittlerer Zauberer") {
                            val numberOfWizards = layedCards.entries.count { it.value.color == Color.MAGICIAN }
                            val winningWizardIndex = (numberOfWizards - 1) / 2
                            layedCards.entries.filter { it.value.color == Color.MAGICIAN  }[winningWizardIndex].key
                        }
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

    suspend fun layCard(name: String, card: Card) {
        if (name != currentPlayer || isPredict) return
        val playerCards = cards[name]!!
        if (card !in playerCards) return
        firstCard?.let { fc ->
            if (card.color == Color.MAGICIAN || card.color == Color.FOOL || card == Card(
                    Color.Special,
                    1
                ) || fc.color == Color.MAGICIAN
            ) return@let
            if (fc.color != card.color && playerCards.any { it.color == fc.color }) return
        }
        if (layedCards.values.all { it.color == Color.FOOL }) {
            firstCard = card
        }
        layedCards[name] = card
        playerCards.remove(card)
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
                if (username == owner && phase == GamePhase.LOBBY)
                    changeRule(msg.rule, msg.value)
            }

            is LayCard -> {
                layCard(username, msg.card)
            }

            else -> {
                logger.warn { "Unknown message: $msg" }
            }
        }
    }

    companion object {
        val NOTHINGCARD = Card(Color.NOTHING, -1)
        val BOMB = Card(Color.Special, 1)
        val logger = KotlinLogging.logger {}
    }

}

@Serializable
enum class Rules(val options: List<String>) {
    @SerialName("Punkte")
    POINTS(listOf("Normal", "Max. 30")),

    @SerialName("Zauberer")
    MAGICIAN(
        listOf(
            "Normal", "Mittlerer Zauberer", "Letzter Zauberer"
        )
    ),

    @SerialName("Ansage")
    PREDICTION(listOf("Nacheinander", "Blind")),

    @SerialName("Trumpf")
    TRUMP(listOf("Normal", "Nur Farben")),

    @SerialName("Spezialkarten")
    SPECIALCARDS(listOf("Aktiviert", "Deaktiviert")),
}

interface SpecialRole {}

enum class FunctionalSpecialRole(val inGameName: String, val description: String) : SpecialRole{
    BLASTER("Der Sprengmeister",
        "Du bekommst immer die Bombe, falls sie im Spiel ist"),
    HEADFOOL("Der Obernarr",
        "Du bekommst immer den ersten Narren im Stapel, bevor normal ausgeteilt wird"),
    SERVANT("Der Knecht",
        "Du bekommts immer so viele Knechte/Mägde wie möglich, bevor normal ausgeteilt wird"),
    GLEEFUL("Der Schadenfrohe",
        "Du bekommst 3 Punkte jedes mal wenn ein anderer Spieler Minuspunkte bekommt"),
    PESSIMIST("Der Pessimist",
        "Du bekommst 50 Punkte wenn du korrekt angesagt 0 Stiche machst, kannst dafür aber maximal 70 Punkte pro Runde machen"),
    OPTIMIST("Der Optimist",
        "Du bekommts 5 Punkte pro gemachtem Stich wenn du eins neben deiner Vorhersage liegts, dafür aber nur 5, 15, 25 bzw. 35 Punkte für 0 bis 3 Stiche")
}

enum class ColorPreferenceSpecialRole(val inGameName: String, val description: String, val color: Color, val chance: Int) : SpecialRole {
    WIZARDMASTER("Der Zaubermeister",
        "Du hast jedes mal wenn ein anderer einen Zauberer ausgetetilt bekommt eine 1/4 Chance ihn zu stehlen.", Color.MAGICIAN, 4),
    REDSHEEP("Das rote Schaf",
        "Du hast jedes mal wenn ein anderer eine rote Karte ausgetetilt bekommt eine 1/2 Chance sie zu stehlen.", Color.RED, 2),
    YELLOWSHEEP("Das gelbe Schaf",
        "Du hast jedes mal wenn ein anderer eine gelbe Karte ausgetetilt bekommt eine 1/2 Chance sie zu stehlen.", Color.YELLOW, 2),
    GREENSHEEP("Das grüne Schaf",
        "Du hast jedes mal wenn ein anderer eine grüne Karte ausgetetilt bekommt eine 1/2 Chance sie zu stehlen.", Color.GREEN, 2),
    BLUESHEEP("Das blaue Schaf",
        "Du hast jedes mal wenn ein anderer eine blaue Karte ausgetetilt bekommt eine 1/2 Chance sie zu stehlen.", Color.BLUE, 2)
}

enum class GamePhase {
    LOBBY, RUNNING, FINISHED
}
