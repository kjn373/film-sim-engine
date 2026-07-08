package app.filmengine.backend

import app.filmengine.backend.auth.LoginRequest
import app.filmengine.backend.auth.RefreshRequest
import app.filmengine.backend.auth.RegisterRequest
import app.filmengine.backend.auth.TokenResponse
import app.filmengine.backend.platform.Database
import app.filmengine.backend.platform.Db
import app.filmengine.backend.users.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthFlowTest {

    companion object {
        private val pg = PostgreSQLContainer("postgres:16-alpine")
        private lateinit var db: Db
        private lateinit var config: AppConfig

        @BeforeAll
        @JvmStatic
        fun boot() {
            pg.start()
            config = AppConfig(
                port = 0,
                dbUrl = pg.jdbcUrl,
                dbUser = pg.username,
                dbPassword = pg.password,
                jwtSecret = "test-secret-that-is-long-enough-for-hmac256",
            )
            db = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
        }

        @AfterAll
        @JvmStatic
        fun shutdown() {
            pg.stop()
        }
    }

    private fun api(block: suspend (HttpClient) -> Unit) = testApplication {
        application { appModule(config, db) }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String, handle: String, password: String) =
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, handle, password))
        }

    @Test
    fun `full auth flow - register, me, login, refresh rotation`() = api { client ->
        val reg = client.register("karjen@example.com", "Karjen", "hunter2hunter2")
        assertEquals(HttpStatusCode.Created, reg.status)
        val tokens = reg.body<TokenResponse>()

        // access token works
        val me = client.get("/v1/users/me") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        val user = me.body<UserResponse>()
        assertEquals("karjen", user.handle)
        assertEquals("karjen@example.com", user.email)

        // no token -> 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/users/me").status)

        // duplicate email/handle -> 409
        assertEquals(
            HttpStatusCode.Conflict,
            client.register("karjen@example.com", "karjen", "hunter2hunter2").status,
        )

        // wrong password -> 401, right password -> 200
        val badLogin = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("karjen@example.com", "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, badLogin.status)
        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("KARJEN@example.com", "hunter2hunter2")) // case-insensitive email
        }
        assertEquals(HttpStatusCode.OK, login.status)

        // refresh rotates: first use succeeds, reuse is rejected
        val r1 = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, r1.status)
        val rotated = r1.body<TokenResponse>()
        val reuse = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)

        // the rotated token still works
        val r2 = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(rotated.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, r2.status)
    }

    @Test
    fun `register validates its inputs`() = api { client ->
        assertEquals(HttpStatusCode.BadRequest, client.register("not-an-email", "okhandle", "hunter2hunter2").status)
        assertEquals(HttpStatusCode.BadRequest, client.register("a@b.co", "x", "hunter2hunter2").status)
        assertEquals(HttpStatusCode.BadRequest, client.register("a@b.co", "okhandle", "short").status)
        assertEquals(HttpStatusCode.BadRequest, client.register("a@b.co", "bad handle!", "hunter2hunter2").status)
    }

    @Test
    fun `garbage tokens are rejected`() = api { client ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/users/me") { bearerAuth("not.a.jwt") }.status,
        )
        val refresh = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest("bogus-token"))
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
    }
}
