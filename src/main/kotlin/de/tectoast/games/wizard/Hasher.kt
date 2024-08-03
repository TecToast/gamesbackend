package de.tectoast.games.wizard

import io.ktor.util.*
import java.security.MessageDigest
import java.security.SecureRandom

object Hasher {
    private val rnd = SecureRandom()
    fun hashPassword(password: String) = MessageDigest.getInstance("SHA-256").digest(password.encodeToByteArray()).encodeBase64()
    fun generateToken(username: String) = hashPassword(username + System.currentTimeMillis() + rnd.nextLong())
}
