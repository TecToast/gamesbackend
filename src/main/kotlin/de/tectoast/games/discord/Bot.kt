package de.tectoast.games.discord

import de.tectoast.games.Config
import de.tectoast.games.db
import de.tectoast.games.jeopardy.JeopardyDataDB
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.interactions.commands.Subcommand
import dev.minn.jda.ktx.interactions.components.EntitySelectMenu
import dev.minn.jda.ktx.interactions.components.into
import dev.minn.jda.ktx.jdabuilder.default
import dev.minn.jda.ktx.messages.into
import dev.minn.jda.ktx.messages.reply_
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget
import net.dv8tion.jda.api.requests.GatewayIntent
import org.litote.kmongo.eq
import org.litote.kmongo.set
import org.litote.kmongo.setTo

val jda by lazy { delegateJda ?: error("JDA not initialized!") }

private var delegateJda: JDA? = null

fun initJDA(config: Config) {
    delegateJda = default(config.discordBotToken, intent = GatewayIntent.GUILD_MEMBERS)
    if(config.devMode) return
    jda.listener<ReadyEvent> {
        jda.getGuildById(1036657324909146153)!!.upsertCommand(
            Commands.slash("jeopardy", "Jeopardy Controls").addSubcommands(
                Subcommand("setusers", "Set the users for a quiz") {
                    addOption(OptionType.STRING, "name", "The name of your jeopardy quiz", true, true)
                },
            )
        ).queue()
    }
    jda.listener<CommandAutoCompleteInteractionEvent> {
        val str = it.focusedOption.value
        when (it.name) {
            "jeopardy" -> {
                when (it.focusedOption.name) {
                    "name" -> {
                        val quizzes = db.jeopardy.find(JeopardyDataDB::user eq it.user.idLong).toList()
                        it.replyChoiceStrings(quizzes.map { q -> q.id }.filter { q -> q.startsWith(str) }).queue()
                    }
                }
            }
        }
    }
    jda.listener<EntitySelectInteractionEvent> {
        val split = it.componentId.split(";")
        when (split[0]) {
            "jeopardy:users" -> {
                val quizName = split[1]
                val users = it.interaction.mentions.users.map { v -> v.id }
                db.jeopardy.updateOne(JeopardyDataDB::id eq quizName, set(JeopardyDataDB::participants setTo users))
                it.reply_("Users set! Refresh the website!", ephemeral = true).queue()
            }
        }
    }
    jda.listener<SlashCommandInteractionEvent> {
        when (it.name) {
            "jeopardy" -> {
                when (it.subcommandName) {
                    "setusers" -> {
                        val quizName = it.getOption("name")!!.asString
                        db.jeopardy.findOne(JeopardyDataDB::user eq it.user.idLong, JeopardyDataDB::id eq quizName)
                            ?: run {
                                it.reply_("Quiz not found!", ephemeral = true).queue()
                                return@listener
                            }
                        it.reply_(
                            "Choose the users for this quiz!",
                            components = EntitySelectMenu(
                                "jeopardy:users;${quizName}",
                                types = SelectTarget.USER.into(),
                                valueRange = 1..25
                            ).into(),
                            ephemeral = true
                        ).queue()
                    }
                }
            }
        }
    }
}
