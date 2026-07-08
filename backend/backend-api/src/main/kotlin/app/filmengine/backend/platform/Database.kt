package app.filmengine.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

object Database {
    fun connect(jdbcUrl: String, user: String, password: String): Db {
        val ds = HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                maximumPoolSize = 10
            }
        )
        Flyway.configure().dataSource(ds).load().migrate()
        return Db(ds)
    }
}

class Db(private val ds: DataSource) {
    /** One transaction per call, executed off the event loop (JDBC blocks). */
    suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }
}
