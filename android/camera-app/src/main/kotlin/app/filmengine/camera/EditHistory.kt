package app.filmengine.camera

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * B8 edit persistence: one session per source image, versions append-only.
 * A version's [EditVersionEntity.graphJson] is a full RecipeCodec document —
 * the recipe wire format — so any stored version is directly sharable and
 * survives editor-model changes via the recipe migration chain.
 */
@Entity(tableName = "edit_sessions", indices = [Index("imageUri", unique = true)])
data class EditSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val createdAt: Long,
)

@Entity(tableName = "edit_versions", indices = [Index("sessionId")])
data class EditVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 1-based, strictly increasing per session — never reused, never rewritten. */
    val seq: Int,
    val graphJson: String,
    val createdAt: Long,
)

/** Append-only by construction: no update or delete queries exist for versions. */
@Dao
interface EditHistoryDao {
    @Query("SELECT * FROM edit_sessions WHERE imageUri = :uri LIMIT 1")
    suspend fun sessionFor(uri: String): EditSessionEntity?

    @Insert
    suspend fun insertSession(session: EditSessionEntity): Long

    @Insert
    suspend fun insertVersion(version: EditVersionEntity): Long

    @Query("SELECT * FROM edit_versions WHERE sessionId = :sessionId ORDER BY seq")
    fun versions(sessionId: Long): Flow<List<EditVersionEntity>>

    @Query("SELECT MAX(seq) FROM edit_versions WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: Long): Int?
}

@Database(entities = [EditSessionEntity::class, EditVersionEntity::class], version = 1)
abstract class EditDatabase : RoomDatabase() {
    abstract fun editHistoryDao(): EditHistoryDao
}
