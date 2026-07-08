package app.filmengine.backend

import app.filmengine.backend.auth.AuthService
import app.filmengine.backend.auth.JwtIssuer
import app.filmengine.backend.auth.authRoutes
import app.filmengine.backend.platform.Database
import app.filmengine.backend.platform.Db
import app.filmengine.backend.platform.installProblemPages
import app.filmengine.backend.users.UserRepo
import app.filmengine.backend.users.userRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
) {
    companion object {
        fun fromEnv() = AppConfig(
            port = System.getenv("PORT")?.toInt() ?: 8080,
            dbUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/filmengine",
            dbUser = System.getenv("DATABASE_USER") ?: "filmengine",
            dbPassword = System.getenv("DATABASE_PASSWORD") ?: "filmengine",
            jwtSecret = System.getenv("JWT_SECRET") ?: error("JWT_SECRET is required"),
        )
    }
}

fun main() {
    val config = AppConfig.fromEnv()
    val db = Database.connect(config.dbUrl, config.dbUser, config.dbPassword)
    embeddedServer(Netty, port = config.port) { appModule(config, db) }.start(wait = true)
}

/** One Ktor module; service boundaries live in packages (ARCHITECTURE.md D6). */
fun Application.appModule(config: AppConfig, db: Db) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    installProblemPages()

    val jwt = JwtIssuer(config.jwtSecret)
    install(Authentication) {
        jwt("jwt") {
            verifier(jwt.verifier)
            validate { cred -> cred.subject?.let { JWTPrincipal(cred.payload) } }
        }
    }

    val users = UserRepo()
    val auth = AuthService(db, users, jwt)

    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(auth)
        userRoutes(db, users)
    }
}
