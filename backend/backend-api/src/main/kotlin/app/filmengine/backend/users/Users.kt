package app.filmengine.backend.users

import app.filmengine.backend.platform.ApiException
import app.filmengine.backend.platform.Db
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

data class UserRow(val id: UUID, val email: String, val handle: String, val passwordHash: String)

@Serializable
data class UserResponse(val id: String, val email: String, val handle: String, val displayName: String)

class UserRepo {

    fun create(c: Connection, email: String, handle: String, passwordHash: String): UUID {
        try {
            val id = c.prepareStatement("INSERT INTO users (email, handle) VALUES (?, ?) RETURNING id").use { st ->
                st.setString(1, email)
                st.setString(2, handle)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
            c.prepareStatement("INSERT INTO auth_credentials (user_id, password_hash) VALUES (?, ?)").use { st ->
                st.setObject(1, id)
                st.setString(2, passwordHash)
                st.executeUpdate()
            }
            return id
        } catch (e: SQLException) {
            if (e.sqlState == "23505") throw ApiException(HttpStatusCode.Conflict, "Email or handle already taken")
            throw e
        }
    }

    fun findByEmail(c: Connection, email: String): UserRow? =
        c.prepareStatement(
            """
            SELECT u.id, u.email, u.handle, a.password_hash
            FROM users u JOIN auth_credentials a ON a.user_id = u.id
            WHERE u.email = ?
            """.trimIndent()
        ).use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else UserRow(
                    rs.getObject(1, UUID::class.java),
                    rs.getString(2), rs.getString(3), rs.getString(4),
                )
            }
        }

    fun findById(c: Connection, id: UUID): UserResponse? =
        c.prepareStatement("SELECT id, email, handle, display_name FROM users WHERE id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else UserResponse(
                    rs.getObject(1, UUID::class.java).toString(),
                    rs.getString(2), rs.getString(3), rs.getString(4),
                )
            }
        }
}

fun Route.userRoutes(db: Db, users: UserRepo) {
    authenticate("jwt") {
        get("/v1/users/me") {
            val principal = call.principal<JWTPrincipal>()
                ?: throw ApiException(HttpStatusCode.Unauthorized, "Missing token")
            val id = UUID.fromString(principal.subject)
            val user = db.tx { c -> users.findById(c, id) }
                ?: throw ApiException(HttpStatusCode.NotFound, "User not found")
            call.respond(user)
        }
    }
}
