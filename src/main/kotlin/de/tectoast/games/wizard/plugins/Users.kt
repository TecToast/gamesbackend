package de.tectoast.games.wizard.plugins

import de.tectoast.games.wizard.Hasher
import de.tectoast.games.wizard.model.Login
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

class UserService(private val database: Database) {
    object Users : Table("users") {
        val username = varchar("username", 30)
        val password = varchar("password", length = 64).nullable()
        val token = varchar("token", length = 64).nullable()

        override val primaryKey = PrimaryKey(username)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Users)
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun byLogin(login: Login) = dbQuery {
        val result = Users.select { Users.username eq login.username }.firstOrNull() ?: return@dbQuery null
        val hash = Hasher.hashPassword(login.username + login.password)
        if (result[Users.password] == null) Users.update({ Users.username eq login.username }) {
            it[password] = hash
        } else {
            if (result[Users.password] != hash) return@dbQuery null
        }
        var token = result[Users.token]
        if(token == null) {
            token = Hasher.generateToken(login.username)
            Users.update({ Users.username eq login.username }) {
                it[Users.token] = token
            }
        }
        return@dbQuery token
    }

    suspend fun byToken(token: String) = dbQuery {
        val result = Users.select { Users.token eq token }.firstOrNull() ?: return@dbQuery null
        return@dbQuery result[Users.username]
    }

    fun register(user: Login) {
        transaction(database) {
            Users.insert {
                it[username] = user.username
                it[password] = Hasher.hashPassword(user.username + user.password)
                it[token] = ""
            }
        }
    }

}
