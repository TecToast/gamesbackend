package de.tectoast.games.discord

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import de.tectoast.games.Config
import de.tectoast.games.db
import de.tectoast.games.utils.GUILD_ID
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.interactions.commands.Command
import dev.minn.jda.ktx.interactions.commands.choice
import dev.minn.jda.ktx.interactions.commands.option
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
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget
import net.dv8tion.jda.api.requests.GatewayIntent

val jda by lazy { delegateJda ?: error("JDA not initialized!") }

private var delegateJda: JDA? = null

fun initJDA(config: Config) {
    if (config.discordBotToken == "secret") return
    delegateJda = default(
        config.discordBotToken,
        intents = listOf(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
    )
//    if (config.devMode) return
    jda.listener<ReadyEvent> {
        val guild = jda.getGuildById(GUILD_ID)!!
        guild.upsertCommand(
            Command("setusers", "Set users for a quiz") {
                option<String>("quiz", "The type of quiz you want to set the users for", required = true) {
                    choice("Jeopardy", "jeopardy")
                    choice("Music Quiz", "musicquiz")
                    choice("Nobody is perfect", "nobodyisperfect")
                }
                option<String>("name", "The name of the quiz", required = true, autocomplete = true)
            }
        ).queue()
        guild.upsertCommand("nobodyisperfect", "Your answer to the question").apply {
            option<String>("answer", "Your answer to the question", required = true, autocomplete = true)
        }.queue()
    }
    jda.listener<CommandAutoCompleteInteractionEvent> {
        val str = it.focusedOption.value
        when (it.name) {
            "setusers" -> {
                when (it.focusedOption.name) {
                    "name" -> {
                        val quiz = it.getOption("quiz")?.asString
                            ?: return@listener it.replyChoiceStrings("You have to provide a quiz.").queue()
                        val database = quiz.parseDatabase { s -> it.replyChoiceStrings(s).queue() } ?: return@listener
                        val quizzes = database.find(Filters.eq("user", it.user.idLong)).toList()
                        it.replyChoiceStrings(quizzes.map { q -> q.id }.filter { q -> q.startsWith(str) }).queue()
                    }
                }
            }
        }
    }
    jda.listener<EntitySelectInteractionEvent> {
        val split = it.componentId.split(";")
        when (split[0]) {
            "users" -> {
                val quiz = split[1]
                val quizName = split[2]
                val users = it.interaction.mentions.users.map { v -> v.id }
                val database = quiz.parseDatabase { s -> it.reply_(s, ephemeral = true).queue() } ?: return@listener
                database.updateOne(Filters.eq("id", quizName), Updates.set("participants", users))
                it.reply_("Users set! Refresh the website!", ephemeral = true).queue()
            }
        }
    }
    jda.listener<SlashCommandInteractionEvent> {
        when (it.name) {
            "setusers" -> {
                val quiz = it.getOption("quiz")!!.asString
                val quizName = it.getOption("name")!!.asString
                val database = quiz.parseDatabase { s -> it.reply_(s, ephemeral = true).queue() } ?: return@listener
                database.findOne(Filters.eq("user", it.user.idLong), Filters.eq("id", quizName))
                    ?: run {
                        it.reply_("Quiz not found!", ephemeral = true).queue()
                        return@listener
                    }
                it.reply_(
                    "Choose the users for this quiz!",
                    components = EntitySelectMenu(
                        "users;$quiz;${quizName}",
                        types = SelectTarget.USER.into(),
                        valueRange = 1..25
                    ).into(),
                    ephemeral = true
                ).queue()
            }

        }
    }

}

inline fun String.parseDatabase(answer: (String) -> Unit) = when (this) {
    "jeopardy" -> db.jeopardy
    "musicquiz" -> db.musicquiz
    "nobodyisperfect" -> db.nobodyIsPerfect
    else -> null.also { answer("Invalid quiz type.") }
}
