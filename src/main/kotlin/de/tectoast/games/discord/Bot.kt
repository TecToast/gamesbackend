package de.tectoast.games.discord

import de.tectoast.games.Config
import dev.minn.jda.ktx.jdabuilder.default
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.requests.GatewayIntent

val jda by lazy { delegateJda ?: error("JDA not initialized!") }

private var delegateJda: JDA? = null

fun initJDA(config: Config) {
    delegateJda = default(config.discordBotToken, intent = GatewayIntent.GUILD_MEMBERS)
}
