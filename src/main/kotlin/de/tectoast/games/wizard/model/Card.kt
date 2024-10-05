package de.tectoast.games.wizard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Color(val text: String) {
    @SerialName("Rot")
    RED("Rot"),
    @SerialName("Blau")
    BLUE("Blau"),
    @SerialName("Grün")
    GREEN("Grün"),
    @SerialName("Gelb")
    YELLOW("Gelb"),
    @SerialName("Zauberer")
    MAGICIAN("Zauberer"),
    @SerialName("Narr")
    FOOL("Narr"),
    @SerialName("Spezial")
    Special("Spezial"),
    @SerialName("Nichts")
    NOTHING("Nichts")
}
@Serializable
data class Card(val color: Color, val value: Int)

@Serializable
data class LayedCard(val card: Card, val player: String)
