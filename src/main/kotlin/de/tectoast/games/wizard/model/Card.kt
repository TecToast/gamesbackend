package de.tectoast.games.wizard.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Color(val text: String, val isNormalColor: Boolean = false) {
    @SerialName("Rot")
    RED("Rot", true),

    @SerialName("Gelb")
    YELLOW("Gelb", true),

    @SerialName("Grün")
    GREEN("Grün", true),

    @SerialName("Blau")
    BLUE("Blau", true),

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
data class Card(val color: Color, val value: Float)

@Serializable
data class LayedCard(val card: Card, val player: String)
