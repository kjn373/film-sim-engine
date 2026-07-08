package app.filmengine.backend.auth

import app.filmengine.backend.platform.ApiException
import app.filmengine.backend.platform.Db
import app.filmengine.backend.users.UserRepo
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable
data class RegisterRequest(val email: String, val handle: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

object PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(password: String): String {
        val chars = password.toCharArray()
        return try {
            argon2.hash(3, 1 shl 16, 1, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    fun verify(hash: String, password: String): Boolean {
        val chars = password.toCharArray()
        return try {
            argon2.verify(hash, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }
}

class JwtIssuer(secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun access(userId: UUID): String = JWT.create()
        .withIssuer(ISSUER)
        .withSubject(userId.toString())
        .withExpiresAt(Instant.now().plusSeconds(ACCESS_TTL_SECONDS))
        .sign(algorithm)

    companion object {
        const val ISSUER = "filmengine"
        const val ACCESS_TTL_SECONDS = 900L
    }
}

class AuthService(private val db: Db, private val users: UserRepo, private val jwt: JwtIssuer) {

    suspend fun register(req: RegisterRequest): TokenResponse {
        if (!EMAIL.matches(req.email)) throw ApiException(HttpStatusCode.BadRequest, "Invalid email")
        val handle = req.handle.lowercase()
        if (!HANDLE.matches(handle)) {
            throw ApiException(HttpStatusCode.BadRequest, "Handle must be 3-30 chars of a-z, 0-9, _")
        }
        if (req.password.length < 8) {
            throw ApiException(HttpStatusCode.BadRequest, "Password must be at least 8 characters")
        }
        val hash = PasswordHasher.hash(req.password)
        return db.tx { c ->
            val id = users.create(c, req.email.lowercase(), handle, hash)
            issue(c, id)
        }
    }

    suspend fun login(req: LoginRequest): TokenResponse = db.tx { c ->
        val user = users.findByEmail(c, req.email.lowercase())
        // Verify against a dummy hash on unknown users so timing doesn't leak existence.
        if (user == null) {
            PasswordHasher.verify(DUMMY_HASH, req.password)
            throw ApiException(HttpStatusCode.Unauthorized, "Invalid credentials")
        }
        if (!PasswordHasher.verify(user.passwordHash, req.password)) {
            throw ApiException(HttpStatusCode.Unauthorized, "Invalid credentials")
        }
        issue(c, user.id)
    }

    /** Rotation: a refresh token is single-use — consumed atomically, replaced. */
    suspend fun refresh(req: RefreshRequest): TokenResponse = db.tx { c ->
        val userId = c.prepareStatement(
            "DELETE FROM refresh_tokens WHERE token_hash = ? AND expires_at > now() RETURNING user_id"
        ).use { st ->
            st.setString(1, sha256(req.refreshToken))
            st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        } ?: throw ApiException(HttpStatusCode.Unauthorized, "Invalid or expired refresh token")
        issue(c, userId)
    }

    private fun issue(c: Connection, userId: UUID): TokenResponse {
        val refresh = randomToken()
        c.prepareStatement(
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, now() + interval '30 days')"
        ).use { st ->
            st.setObject(1, userId)
            st.setString(2, sha256(refresh))
            st.executeUpdate()
        }
        return TokenResponse(jwt.access(userId), refresh, JwtIssuer.ACCESS_TTL_SECONDS)
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val HANDLE = Regex("^[a-z0-9_]{3,30}$")
        val DUMMY_HASH: String = PasswordHasher.hash("timing-equalizer")
        val RANDOM = SecureRandom()

        fun randomToken(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun sha256(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

fun Route.authRoutes(auth: AuthService) {
    route("/v1/auth") {
        post("register") { call.respond(HttpStatusCode.Created, auth.register(call.receive())) }
        post("login") { call.respond(auth.login(call.receive())) }
        post("refresh") { call.respond(auth.refresh(call.receive())) }
    }
}
