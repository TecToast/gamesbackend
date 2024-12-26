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
    var playersRemainingForWinnerVoting = mutableSetOf<String>()
    var winnerVotingTally = mutableMapOf<String, Int>()
    var phase = GamePhase.LOBBY
    var currentPlayer = "Ofl"
    var roundPlayers = mutableListOf<String>()
    var round = 0
    var isPredict = true
    var reversedPlayOrder = false
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

            if (checkRule(Rules.MEMECARDS) == "Aktiviert") {
                add(DEEZNUTS)
                add(TROLL)
                add(STONKS)
                add(BLOCKED)
                add(REVERSE)
                add(DEMOCRACY)
                add(GAMBLING)
            }
        }
    }

    var userToChangeStitchPrediction: String? = null
    var isSevenPointFiveUsed = false
    val cardsToChange = mutableMapOf<String, Card>()
    lateinit var winner: String
    var stitchValue = 0

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

    private fun generateOrder(beginningPlayerOffset: Int, respectReversedOrder: Boolean): MutableList<String> {
        val direction = if (respectReversedOrder && reversedPlayOrder) -1 else 1
        val list = ArrayList<String>(players.size)
        val pl = players.toList()
        for (i in pl.indices) {
            list.add(pl[((i * direction) + beginningPlayerOffset + pl.size) % pl.size])
        }
        return list
    }

    fun generateStitchOrder() = generateOrder(beginningPlayerOffset = round - 1, respectReversedOrder = false)
    fun generatePlayOrder() = generateOrder(beginningPlayerOffset = round, respectReversedOrder = true)
    fun generateNextPlayOrder(winnerIndex: Int) = generateOrder(beginningPlayerOffset = winnerIndex, respectReversedOrder = true)

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

    suspend fun endGame() = broadcast(EndGame(players.sortedByDescending { points[it]!! }.map {
        PlayerPoints(buildString {
            append(it)
            if (checkRule(Rules.SPECIALROLES) != "Deaktiviert") {
                append(" - ")
                append(specialRoles.entries.filter { roleEntry -> roleEntry.value == it }
                    .joinToString { it.key.inGameName })
            }
        }, points[it]!!)
    }))

    suspend fun nextRound(nodelay: Boolean) {
        delay(if (nodelay) 0 else 1000)
        if (++round * players.size > allCards.size) {
            endGame()
            return
        }
        giveCards(round)
        roundPlayers = generateStitchOrder()
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

                    var stolenBy: String? = null
                    var replacedCard: Card? = null
                    specialRoles.entries.firstOrNull { (it.key as? ColorPreferenceSpecialRole)?.color == nextCard.color }
                        ?.takeIf {
                            rnd.nextInt((it.key as ColorPreferenceSpecialRole).chance) == 0
                        }?.let {
                            if (cards.getValue(it.value).size >= round) replacedCard = cards.getValue(it.value).random()
                            cards.getValue(it.value).add(nextCard)
                            stolenBy = it.value
                        }
                    if (nextCard == BOMB) {
                        specialRoles[FunctionalSpecialRole.BLASTER]?.let { blaster ->
                            if (cards.getValue(blaster).size >= round) replacedCard = cards.getValue(blaster).random()
                            cards.getValue(blaster).add(nextCard)
                            stolenBy = blaster
                        }
                    }

                    if (stolenBy == null) {
                        cards.getOrPut(player) { mutableListOf() }.add(nextCard)
                    } else if (replacedCard != null) {
                        cards[stolenBy]!!.remove(replacedCard)
                        cards.getOrPut(player) { mutableListOf() }.add(replacedCard)
                    }
                }
            }
        }
        val shifted = mutableMapOf<String, Int>()
        trump = stack.removeFirstOrNull() ?: NOTHINGCARD

        val forbidden = if (checkRule(Rules.TRUMP) == "Nur Farben") {
            listOf(Color.MAGICIAN, Color.FOOL, Color.SPECIAL)
        } else {
            listOf()
        }

        while (skipTrump(trump, forbidden)) {
            shifted.add(trump.color.text, 1)
            trump = stack.removeFirstOrNull() ?: NOTHINGCARD
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

    fun skipTrump(trumpCard: Card, forbidden: List<Color>): Boolean {
        if (trumpCard == NOTHINGCARD) return false
        val presentColorPreferences: List<Color> = buildList {
            specialRoles.keys.forEach {
                if (it is ColorPreferenceSpecialRole && it != ColorPreferenceSpecialRole.WIZARDMASTER) add(it.color)
            }
        }
        return forbidden.contains(trumpCard.color) || (presentColorPreferences.isNotEmpty() && !presentColorPreferences.contains(
            trumpCard.color
        ) && rnd.nextInt(2) == 0)
    }

    fun String.normalPointCalculation() =
        (if (stitchDone[this] == stitchGoals[this]) 20 + stitchDone[this]!! * 10 else -10 * abs(stitchDone[this]!! - stitchGoals[this]!!))

    fun String.predictedCorrectly() = stitchDone[this] == stitchGoals[this]

    /*
    * winner ist hier nie null, sondern gibt immer den Spieler mit dem höchsten gelegten Kartenwert an
     */
    suspend fun afterSubRound(wasBombUsed: Boolean, everybodyPointsUsed: Boolean, stitchValue: Int) {
        if (!wasBombUsed) {
            players.filter {(it == winner) xor everybodyPointsUsed}.forEach {
                    stitchDone.add(it, stitchValue)
                    broadcast(UpdateDoneStitches(it, stitchDone[it]!!))
                }
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
                val done = stitchDone[p]!!
                val goal = stitchGoals[p]!!
                val difference = abs(goal - done)
                val amount = when (p) {
                    specialRoles[FunctionalSpecialRole.GAMBLER] -> {
                        if (p.predictedCorrectly()) done * 20
                        else -20 * abs(difference)
                    }

                    specialRoles[FunctionalSpecialRole.PESSIMIST] -> {
                        if (p.predictedCorrectly() && done == 0) 50
                        else p.normalPointCalculation().coerceAtMost(20 + 10 * (12 / players.size))
                    }

                    specialRoles[FunctionalSpecialRole.OPTIMIST] -> {
                        if (p.predictedCorrectly()) {
                            done * 10 + if (done < (12 / players.size)) 0 else 20
                        } else if (difference == 1) {
                            5 * done
                        } else {
                            -10 * difference
                        }
                    }

                    specialRoles[FunctionalSpecialRole.GREEDY] -> {
                        if (done >= goal) 5 * (goal + done)
                        else -10 * difference
                    }

                    else -> {
                        p.normalPointCalculation()
                    }
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
            if (checkRule(Rules.SPECIALROLES) != "Geheim") {
                broadcast(Results(results))
            } else {
                for (player in players) {
                    player.send(Results(results.filterKeys { it == player }))
                }
            }
            nextRound(false)
        } else {
            roundPlayers = generateNextPlayOrder(players.indexOf(winner))
            originalOrderForSubround = roundPlayers.toList()
            currentPlayer = roundPlayers.removeFirst()
            broadcast(CurrentPlayer(currentPlayer))
        }
    }

    fun filterBlockedPlayers() {
        for ((index, playerToCheck) in originalOrderForSubround.withIndex()) {
            if (layedCards[playerToCheck] == BLOCKED) {
                val nextPlayer = originalOrderForSubround[(index + 1) % players.size]
                layedCards.remove(nextPlayer)
            }
        }
    }

    fun findStitchWinnerNormally() {
        val thiefPlayer = specialRoles[FunctionalSpecialRole.THIEF]
        val dragonIngame = layedCards.values.contains(DRAGON)
        val firstPlayerOfRound = originalOrderForSubround.first { layedCards.contains(it) }

        winner = when {
            layedCards[thiefPlayer]?.let {
                (it.value == 1.0f && it.color.isNormalColor && rnd.nextInt(0, 2) == 0)
            } == true -> thiefPlayer!!

            layedCards.values.containsAll(
                listOf(
                    FAIRY, DRAGON
                )
            ) -> layedCards.entries.find { it.value == FAIRY }!!.key

            dragonIngame -> layedCards.entries.find { it.value == DRAGON }!!.key

            layedCards.entries.filter { it.value != FAIRY }
                .all { it.value.color == Color.FOOL } -> layedCards.entries.first { it.value.color == Color.FOOL }.key

            layedCards.values.any { it.color == Color.MAGICIAN } -> {
                if (checkRule(Rules.MAGICIAN) == "Letzter Zauberer") layedCards.entries.last { it.value.color == Color.MAGICIAN }.key
                else if (checkRule(Rules.MAGICIAN) == "Mittlerer Zauberer") {
                    val numberOfWizards = layedCards.entries.count { it.value.color == Color.MAGICIAN }
                    val winningWizardIndex = (numberOfWizards - 1) / 2
                    layedCards.entries.filter { it.value.color == Color.MAGICIAN }[winningWizardIndex].key
                } else layedCards.entries.first { it.value.color == Color.MAGICIAN }.key
            }

            else -> {
                var highest = layedCards[firstPlayerOfRound]!! to firstPlayerOfRound
                for (i in 1..<players.size) {
                    val playerToCheck = originalOrderForSubround[i]
                    if (!layedCards.contains(playerToCheck)) continue

                    val card = layedCards[playerToCheck]!!
                    if (card.isHigherThan(highest.first)) {
                        highest = card to playerToCheck
                    }
                }
                highest.second
            }
        }
    }

    fun checkForReverseCard() {
        if (layedCards.values.contains(REVERSE)) reversedPlayOrder = !reversedPlayOrder
    }
    fun findStitchEvaluationMethodAndValue() : StitchEvaluationMethod {
        val dragonIngame = layedCards.values.contains(DRAGON)
        stitchValue = 1
        var stitchEvaluationMethod = StitchEvaluationMethod.NORMAL
        originalOrderForSubround.mapNotNull { layedCards[it] }.forEach { card ->
            if (card.value == -1f) stitchValue = -1 // Troll card
            else if (card.value == 14f) stitchValue = 3 // Stonks card
            else if (card.value == 69f && dragonIngame) stitchValue = -3 // Dragon deez nuts combination

            if (card == GAMBLING) stitchEvaluationMethod = StitchEvaluationMethod.RANDOM
            else if (card == DEMOCRACY) stitchEvaluationMethod = StitchEvaluationMethod.POLL
        }
        return stitchEvaluationMethod
    }

    fun evaluateStitch() : Boolean {
        filterBlockedPlayers()
        checkForReverseCard()
        val method = findStitchEvaluationMethodAndValue()
        if (method == StitchEvaluationMethod.POLL) return true
        else if (method == StitchEvaluationMethod.RANDOM) winner = layedCards.keys.random()
        else findStitchWinnerNormally()

        return false
    }

    suspend fun nextPlayer() {
        currentPlayer = roundPlayers.removeFirstOrNull() ?: run {
            if (isPredict) {
                roundPlayers = generatePlayOrder()
                originalOrderForSubround = roundPlayers.toList()
                isPredict = false
                broadcast(IsPredict(false))
                return nextPlayer()
            }
            val pollNecessary = evaluateStitch()
            val bombUsed = layedCards.values.contains(BOMB)
            val everybodyPointsUsed = layedCards.values.contains(EVERYBODYPOINTS)

            if (pollNecessary && !bombUsed) { // Wait for poll results
                broadcast(ShowWinnerPollModal(true))
                playersRemainingForWinnerVoting.addAll(players)
                players.forEach { winnerVotingTally[it] = 0 }
            }
            else afterSubRound(bombUsed, everybodyPointsUsed, stitchValue) // Proceed normally
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
        if (highestCardUntilNow == FAIRY) return true
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
                    Color.FOOL, Color.MAGICIAN, Color.SPECIAL
                ) && fc.color != card.color && fc.color != Color.MAGICIAN && fc != DRAGON && playerCards.any { it.color == fc.color }
            ) return
        }
        if (layedCards.values.all { it.color in setOf(Color.FOOL, Color.SPECIAL) }) {
            if (card == DRAGON || card.color != Color.SPECIAL && card.color != Color.FOOL) {
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
                player.send(SelectedRoles(specialRoles.entries.associate { if (it.value == player) it.value to it.key.inGameName else it.value to "???" }))
            }
        } else broadcast(SelectedRoles(specialRoles.entries.associate { it.value to it.key.inGameName }))
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

            if (checkRule(Rules.SPECIALROLES) == "Geheim") {
                username.send(SelectedRoles(specialRoles.entries.associate { it.value to if (it.value == username) it.key.inGameName else "???" }))
            } else {
                username.send(SelectedRoles(specialRoles.entries.associate { it.value to it.key.inGameName }))
            }

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

            is VoteForWinner -> {
                if (!playersRemainingForWinnerVoting.contains(username)) return
                if (!players.contains(msg.value)) return
                if (msg.value == username) return

                socket.sendWS(ShowWinnerPollModal(false))

                winnerVotingTally[msg.value] = winnerVotingTally[msg.value]!! + 1
                playersRemainingForWinnerVoting.remove(username)

                if (playersRemainingForWinnerVoting.isEmpty()) {
                    winner = originalOrderForSubround.first()
                    originalOrderForSubround.forEach {
                        winner = if (winnerVotingTally[it]!! > winnerVotingTally[winner]!!) it else winner
                    }
                    afterSubRound(layedCards.values.contains(BOMB), layedCards.values.contains(EVERYBODYPOINTS), stitchValue)
                }
            }

            is ChangeStitchPrediction -> {
                if (username != userToChangeStitchPrediction) return
                if (abs(msg.value) != 1) return
                if (stitchGoals[username]!! + msg.value !in 0..round) return //TODO: reconsider upper bound for predictions
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

        val BOMB = Card(Color.SPECIAL, 1f)
        val SEVENPOINTFIVE = Card(Color.SPECIAL, 7.5f)
        val NINEPOINTSEVENFIVE = Card(Color.SPECIAL, 9.75f)
        val FAIRY = Card(Color.SPECIAL, 2f)
        val DRAGON = Card(Color.SPECIAL, 3f)

        val TROLL = Card(Color.SPECIAL, -1f)
        val STONKS = Card(Color.SPECIAL, 14f)
        val DEEZNUTS = Card(Color.SPECIAL, 69f)
        val BLOCKED = Card(Color.FOOL, 6f)
        val REVERSE = Card(Color.FOOL, 5f)
        val EVERYBODYPOINTS = Card(Color.FOOL, 7f)
        val DEMOCRACY = Card(Color.SPECIAL, 7f)
        val GAMBLING = Card(Color.SPECIAL, 6f)

        val logger = KotlinLogging.logger {}
        val rainbowCards = setOf(SEVENPOINTFIVE, NINEPOINTSEVENFIVE, TROLL, DEEZNUTS, STONKS)
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

    @SerialName("Memekarten")
    MEMECARDS(listOf("Aktiviert", "Deaktiviert")),

    @SerialName("Spezialrollen")
    SPECIALROLES(listOf("Deaktiviert", "Freie Auswahl", "Vorgegeben", "Geheim")),
}

interface SpecialRole {
    val inGameName: String
}

enum class FunctionalSpecialRole(override val inGameName: String) : SpecialRole {
    BLASTER("Der Sprengmeister"),
    HEADFOOL("Der Obernarr"),
    SERVANT("Der Knecht"),
    GLEEFUL("Der Schadenfrohe"),
    PESSIMIST("Der Pessimist"),
    OPTIMIST("Der Optimist"),
    GAMBLER("Der Gambler"),
    THIEF("Der Dieb"),
    GREEDY("Der Gierige")
}

enum class ColorPreferenceSpecialRole
    (override val inGameName: String, val color: Color, val chance: Int) : SpecialRole {
    WIZARDMASTER("Der Zaubermeister", Color.MAGICIAN, 4),
    REDSHEEP("Das rote Schaf", Color.RED, 2),
    YELLOWSHEEP("Das gelbe Schaf", Color.YELLOW, 2),
    GREENSHEEP("Das grüne Schaf", Color.GREEN, 2),
    BLUESHEEP("Das blaue Schaf", Color.BLUE, 2)
}

enum class GamePhase {
    LOBBY, ROLESELECTION, RUNNING, FINISHED
}

enum class StitchEvaluationMethod {
    NORMAL, RANDOM, POLL
}
