package app.filmengine.backend.platform

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/** Domain errors carry their HTTP status; everything else is a 500. */
class ApiException(val status: HttpStatusCode, override val message: String) : Exception(message)

@Serializable
data class Problem(val title: String, val status: Int)

fun Application.installProblemPages() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, Problem(e.message, e.status.value))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, Problem("Malformed request body", 400))
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled exception", e)
            call.respond(HttpStatusCode.InternalServerError, Problem("Internal error", 500))
        }
    }
}
