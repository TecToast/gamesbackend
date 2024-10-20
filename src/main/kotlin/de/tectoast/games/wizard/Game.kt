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
    var playersRemainingForRoleSelection = mutableListOf<String>()
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
                add(FAIRY)
                add(DRAGON)
            }
        }
    }

    var userToChangeStitchPrediction: String? = null
    var isSevenPointFiveUsed = false
    val cardsToChange = mutableMapOf<String, Card>()
    lateinit var winner: String

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
        delay(if (nodelay) 0 else 1000)
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

    val forcedCards = listOf<Card>()

    suspend fun giveCards(round: Int) {
        val stack = allCards.shuffled(rnd) as MutableList<Card>
        players.forEach { cards[it] = mutableListOf() }
        forcedCards.forEach { stack.remove(it) }
        val mutableForced = forcedCards.toMutableList()
        specialRoles[FunctionalSpecialRole.HEADFOOL]?.let { user ->
            val firstFool = stack.first { it.color == Color.FOOL }
            stack.remove(firstFool)
            cards.getValue(user).add(firstFool)
        }
        specialRoles[FunctionalSpecialRole.SERVANT]?.let { user ->
            val playerCards = cards.getValue(user)
            for (servantCard in stack.filter {
                it.value == 2f && it.color in setOf(
                    Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE
                )
            }) {
                if (playerCards.size < round) {
                    stack.remove(servantCard)
                    playerCards.add(servantCard)
                } else break
            }
        }

        var enoughCardsDealt = false
        while (!enoughCardsDealt) {
            enoughCardsDealt = true
            for (player in players) {
                if (cards.getValue(player).size < round) {
                    enoughCardsDealt = false
                    val nextCard = mutableForced.removeFirstOrNull() ?: stack.removeFirstOrNull() ?: NOTHINGCARD

                    var cardStolen = false
                    specialRoles.entries.firstOrNull { (it.key as? ColorPreferenceSpecialRole)?.color == nextCard.color }
                        ?.takeIf {
                            cards.getValue(it.value).size < round && rnd.nextInt((it.key as ColorPreferenceSpecialRole).chance) == 0
                        }?.let {
                            cards.getValue(it.value).add(nextCard)
                            cardStolen = true
                        }
                    if (nextCard == BOMB) {
                        specialRoles[FunctionalSpecialRole.BLASTER]?.let { blaster ->
                            if (cards.getValue(blaster).size < round) {
                                cards.getValue(blaster).add(nextCard)
                                cardStolen = true
                            }
                        }
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
                    player.send(Cards(cards[player]!!, NOTHINGCARD))
                }
            }
        }
    }

    fun String.normalPointCalculation() =
        (if (stitchDone[this] == stitchGoals[this]) 20 + stitchDone[this]!! * 10 else -10 * abs(stitchDone[this]!! - stitchGoals[this]!!))

    fun String.predictedCorrectly() = stitchDone[this] == stitchGoals[this]

    /*
    * winner ist hier nie null, sondern gibt immer den Spieler mit dem höchsten gelegten Kartenwert an
     */
    suspend fun afterSubRound(wasBombUsed: Boolean) {
        if (!wasBombUsed) {
            stitchDone.add(winner, 1)
            broadcast(UpdateDoneStitches(winner, stitchDone[winner]!!))
        }
        val wasNinePointsSevenFiveUsed = layedCards.values.any { it.value == 9.75f }
        isSevenPointFiveUsed = layedCards.values.any { it.value == 7.5f }
        if (wasNinePointsSevenFiveUsed) {
            userToChangeStitchPrediction = winner
        }
        layedCards.clear()
        firstCard = null
        broadcast(Winner(winner.takeUnless { wasBombUsed }))
        delay(3300)
        broadcast(ClearForNewSubRound)

        if (wasNinePointsSevenFiveUsed && !wasBombUsed) {
            winner.send(ShowChangeStitchModal(true))
        } else {
            if (isSevenPointFiveUsed && cards[winner]!!.isNotEmpty()) {
                broadcast(SevenPointFiveUsed)
                return
            }
            newSubround()
        }
    }

    suspend fun newSubround() {
        cardsToChange.clear()
        if (cards[currentPlayer]!!.isEmpty()) {
            var numberOfLoosingPlayers = 0
            val results = mutableMapOf<String, Int>()
            players.forEach { p ->
                val amount =
                    if (specialRoles[FunctionalSpecialRole.GAMBLER] == p) {
                        if (p.predictedCorrectly()) stitchDone[p]!! * 20
                        else abs(stitchDone[p]!! - stitchGoals[p]!!) * -20
                    } else if (specialRoles[FunctionalSpecialRole.PESSIMIST] == p) {
                        if (p.predictedCorrectly() && stitchDone[p] == 0) 50
                        else p.normalPointCalculation().coerceAtMost(20 + 10*(10/players.size))
                    } else if (specialRoles[FunctionalSpecialRole.OPTIMIST] == p) {
                        if (p.predictedCorrectly()) {
                            stitchDone[p]!! * 10 + if (stitchDone[p]!! <= (10/players.size)) 0 else 20
                        } else if (abs(stitchDone[p]!! - stitchGoals[p]!!) == 1) {
                            5 * stitchDone[p]!!
                        } else {
                            -10 * abs(stitchDone[p]!! - stitchGoals[p]!!)
                        }
                    } else {
                        p.normalPointCalculation()
                    }

                if (amount < 0 && specialRoles[FunctionalSpecialRole.GLEEFUL] != p) {
                    numberOfLoosingPlayers += 1
                }
                results[p] = amount
            }
            specialRoles[FunctionalSpecialRole.GLEEFUL]?.let { gleeful ->
                results.add(gleeful, numberOfLoosingPlayers * 5)
            }

            for (p in results.keys) {
                if (checkRule(Rules.POINTS) == "Max. 30") results[p] = results[p]!!.coerceAtMost(30)
                addPoints(p, results[p]!!)
            }

            stitchGoals.clear()
            stitchDone.clear()
            isPredict = true
            if (checkRule(Rules.SPECIALROLES) != "Geheim") broadcast(Results(results))
            nextRound(false)
        } else {
            roundPlayers = players.toMutableList()
            while (roundPlayers.first() != winner) roundPlayers.add(roundPlayers.removeFirst())
            originalOrderForSubround = roundPlayers.toList()
            currentPlayer = roundPlayers.removeFirst()
            broadcast(CurrentPlayer(currentPlayer))
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
            winner = when {
                layedCards.values.containsAll(
                    listOf(
                        FAIRY,
                        DRAGON
                    )
                ) -> layedCards.entries.find { it.value == FAIRY }!!.key

                layedCards.values.contains(DRAGON) -> layedCards.entries.find { it.value == DRAGON }!!.key

                layedCards.entries.filter { it.value != FAIRY }
                    .all { it.value.color == Color.FOOL } -> layedCards.entries.first { it.value.color == Color.FOOL }.key

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

            afterSubRound(wasBombUsed)
            return
        }
        broadcast(CurrentPlayer(currentPlayer))
    }

    /**
     * @return true if the card is higher than the first card or trump
     */
    fun Card.isHigherThan(highestCardUntilNow: Card): Boolean {
        if (this == FAIRY) return false
        if (this == DRAGON) return true
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
            if (realCard.color !in setOf(
                    Color.FOOL,
                    Color.MAGICIAN,
                    Color.Special
                ) && fc.color != card.color && fc.color != Color.MAGICIAN && fc != DRAGON && playerCards.any { it.color == fc.color }
            ) return
        }
        if (layedCards.values.all { it.color in setOf(Color.FOOL, Color.Special) }) {
            if (card == DRAGON || card.color != Color.Special && card.color != Color.FOOL) {
                firstCard = card
            }
        }
        layedCards[name] = card
        playerCards.remove(realCard)
        broadcast(PlayerCard(LayedCard(card, name)))
        nextPlayer()
    }

    suspend fun broadcastRoleChoices() {
        if (checkRule(Rules.SPECIALROLES) == "Geheim") {
            for (player in players) {
                player.send(SelectedRoles(specialRoles.entries.associate { if (it.value == player) it.value to it.key.inGameName else it.value to "???"}))
            }
        } else broadcast(SelectedRoles(specialRoles.entries.associate { it.value to it.key.inGameName}))
    }

    suspend fun allowNextPlayerToPickRole() {
        val nextPlayer = playersRemainingForRoleSelection.first()
        broadcastRoleChoices()
        broadcast(CurrentRoleSelectingPlayer(nextPlayer))
    }

    fun getSpecialRoleFromInGameName(inGameName: String): SpecialRole? =
        (FunctionalSpecialRole.entries + ColorPreferenceSpecialRole.entries).firstOrNull { it.inGameName == inGameName }

    suspend fun start() {
        if (phase !in setOf(GamePhase.LOBBY, GamePhase.ROLESELECTION)) return
        players = players.shuffled().toMutableSet()
        players.forEach { points[it] = 0 }
        phase = GamePhase.RUNNING
        broadcast(GameStarted(players))
        broadcastRoleChoices()
        nextRound(true)
    }

    suspend fun sendCurrentState(username: String) {
        with(SocketManager[username]) {
            sendWS(Cards(cards[username].orEmpty(), NOTHINGCARD))
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
            is StartButtonClicked -> {
                if (phase == GamePhase.LOBBY && username == owner) {
                    if (checkRule(Rules.SPECIALROLES) == "Freie Auswahl") {
                        if (specialRoles.isEmpty()) {
                            phase = GamePhase.ROLESELECTION
                            playersRemainingForRoleSelection = players.shuffled().toMutableList()
                            allowNextPlayerToPickRole()
                        }
                    } else if (checkRule(Rules.SPECIALROLES) in setOf("Vorgegeben", "Geheim")) {
                        val allRoles = (ColorPreferenceSpecialRole.entries + FunctionalSpecialRole.entries).shuffled()
                            .toMutableList()
                        for (player in players) {
                            specialRoles[allRoles.removeFirst()] = player
                        }
                        start()
                    } else {
                        start()
                    }
                }
            }

            is RequestSelectedRole -> {
                if (username == playersRemainingForRoleSelection.firstOrNull()) {
                    val requestedRole = getSpecialRoleFromInGameName(msg.roleName)
                    if (requestedRole != null && requestedRole !in specialRoles.keys) {
                        specialRoles[requestedRole] = username
                        playersRemainingForRoleSelection.removeFirst()
                        if (playersRemainingForRoleSelection.isEmpty()) {
                            broadcast(CurrentRoleSelectingPlayer(""))
                            start()
                        } else {
                            allowNextPlayerToPickRole()
                        }
                    }
                }
            }

            is LeaveGame -> {
                // TODO: Check which phase and probably change owner etc (or delete the game)
                // TODO: if all users left: delete room
                when (phase) {
                    GamePhase.LOBBY -> {
                        if (username == owner) {
                            GameManager.removeGame(id)
                        }
                        removePlayer(username)
                        socket.sendWS(RedirectHome)
                    }

                    GamePhase.RUNNING, GamePhase.ROLESELECTION -> {
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

            is ChangeStitchPrediction -> {
                if (username != userToChangeStitchPrediction) return
                if (abs(msg.value) != 1) return
                if (stitchGoals[username]!! + msg.value !in 0..round) return
                socket.sendWS(ShowChangeStitchModal(false))
                delay(200)
                val newPrediction = stitchGoals.add(username, msg.value)!!
                userToChangeStitchPrediction = null
                broadcast(StitchGoal(username, newPrediction))
                if (isSevenPointFiveUsed && cards[username]!!.isNotEmpty()) {
                    broadcast(SevenPointFiveUsed)
                }
                newSubround()
            }

            is ChangeCard -> {
                //TODO: rein theoretisch kann Zuschauer game crashen? Wenn Zuschauer eine Nachricht schickt, was ist dann cards[ZuschauerName]?
                if (!isSevenPointFiveUsed || !cards[username]!!.contains(msg.card) || cardsToChange[username] != null) {
                    return
                }
                cards[username]!!.remove(msg.card)
                cardsToChange[username] = msg.card
                if (cardsToChange.size == players.size) {
                    val playersIterator = players.iterator()
                    val firstPlayer = playersIterator.next()
                    var player = firstPlayer
                    var nextPlayer: String
                    val newCards = mutableMapOf<String, Card>()
                    while (playersIterator.hasNext()) {
                        nextPlayer = playersIterator.next()
                        cards[nextPlayer]!!.add(cardsToChange[player]!!)
                        newCards[nextPlayer] = cardsToChange[player]!!
                        player = nextPlayer
                    }
                    cards[firstPlayer]!!.add(cardsToChange[player]!!)
                    newCards[firstPlayer] = cardsToChange[player]!!
                    coroutineScope {
                        for (user in players) {
                            launch {
                                user.send(Cards(cards[user]!!, newCards[user]!!))
                            }
                        }
                    }
                    isSevenPointFiveUsed = false
                    newSubround()
                }
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
        val FAIRY = Card(Color.Special, 2f)
        val DRAGON = Card(Color.Special, 3f)
        val logger = KotlinLogging.logger {}
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
            "Normal", "Mittlerer Zauberer", "Letzter Zauberer"
        )
    ),

    @SerialName("Ansage")
    PREDICTION(listOf("Nacheinander", "Blind")),

    @SerialName("Trumpf")
    TRUMP(listOf("Normal", "Nur Farben")),

    @SerialName("Spezialkarten")
    SPECIALCARDS(listOf("Aktiviert", "Deaktiviert")),

    @SerialName("Spezialrollen")
    SPECIALROLES(listOf("Deaktiviert", "Freie Auswahl", "Vorgegeben", "Geheim")),
}

interface SpecialRole {
    val inGameName: String
}

enum class FunctionalSpecialRole(override val inGameName: String) : SpecialRole {
    BLASTER("Der Sprengmeister"), HEADFOOL("Der Obernarr"), SERVANT("Der Knecht"), GLEEFUL("Der Schadenfrohe"), PESSIMIST(
        "Der Pessimist"
    ),
    OPTIMIST("Der Optimist"),
    GAMBLER("Der Gambler")
}

enum class ColorPreferenceSpecialRole(override val inGameName: String, val color: Color, val chance: Int) :
    SpecialRole {
    WIZARDMASTER("Der Zaubermeister", Color.MAGICIAN, 4), REDSHEEP(
        "Das rote Schaf",
        Color.RED,
        2
    ),
    YELLOWSHEEP("Das gelbe Schaf", Color.YELLOW, 2), GREENSHEEP(
        "Das grüne Schaf",
        Color.GREEN,
        2
    ),
    BLUESHEEP("Das blaue Schaf", Color.BLUE, 2)
}

enum class GamePhase {
    LOBBY, ROLESELECTION, RUNNING, FINISHED
}
