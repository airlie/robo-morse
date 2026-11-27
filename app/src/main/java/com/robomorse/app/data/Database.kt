package com.robomorse.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "item_stats")
data class ItemStatsEntity(
    @PrimaryKey val symbol: String,
    val acc: Double,
    val rtMs: Double?,
    val bestRtMs: Double?,
    val attempts: Int,
    val correct: Int,
    val srsStabilityDays: Double,
    val srsDueMs: Long,
)

@Entity(tableName = "confusion", primaryKeys = ["expected", "answered"])
data class ConfusionEntity(
    val expected: String,
    val answered: String,
    val count: Int,
)

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val startedAt: Long,
    val endedAt: Long,
    val charWpm: Int,
    val effWpm: Int,
    val attempts: Int,
    val correct: Int,
    val learnedCount: Int,
    val unlocked: String,
)

@Entity(tableName = "attempt")
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val expected: String,
    val answered: String?,
    val correct: Boolean,
    val rtMs: Double?,
    val timestamp: Long,
    val inputMethod: String,
)

/** Accuracy and response time for one character in one session (for trend charts). */
data class CharSessionPoint(
    val sessionId: Long,
    val startedAt: Long,
    val attempts: Int,
    val correct: Int,
    val avgRtMs: Double?,
)

@Dao
interface MorseDao {
    @Query("SELECT * FROM item_stats")
    suspend fun allStats(): List<ItemStatsEntity>

    @Query("SELECT * FROM item_stats")
    fun statsFlow(): Flow<List<ItemStatsEntity>>

    @Upsert
    suspend fun upsertStats(stats: List<ItemStatsEntity>)

    @Query("SELECT * FROM confusion")
    fun confusionFlow(): Flow<List<ConfusionEntity>>

    @Query("SELECT * FROM confusion WHERE expected = :expected AND answered = :answered")
    suspend fun confusion(expected: String, answered: String): ConfusionEntity?

    @Upsert
    suspend fun upsertConfusion(c: ConfusionEntity)

    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Insert
    suspend fun insertAttempts(a: List<AttemptEntity>)

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    fun sessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    /** The latest character answers, newest first. */
    @Query("SELECT * FROM attempt ORDER BY id DESC LIMIT :limit")
    suspend fun recentAttempts(limit: Int): List<AttemptEntity>

    // Everything, for exporting.
    @Query("SELECT * FROM session ORDER BY id") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM attempt ORDER BY id") suspend fun allAttempts(): List<AttemptEntity>
    @Query("SELECT * FROM confusion") suspend fun allConfusions(): List<ConfusionEntity>

    @Query("SELECT * FROM session WHERE id > :afterId AND mode = :mode ORDER BY id")
    suspend fun sessionsAfter(afterId: Long, mode: String): List<SessionEntity>

    @Query(
        """
        SELECT a.sessionId AS sessionId, s.startedAt AS startedAt, COUNT(*) AS attempts,
               SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS correct, AVG(a.rtMs) AS avgRtMs
        FROM attempt a JOIN session s ON s.id = a.sessionId
        WHERE a.expected = :symbol
        GROUP BY a.sessionId ORDER BY s.startedAt
        """,
    )
    suspend fun charHistory(symbol: String): List<CharSessionPoint>

    @Transaction
    suspend fun saveSession(
        session: SessionEntity,
        attempts: List<AttemptEntity>,
        stats: List<ItemStatsEntity>,
        confusions: Map<Pair<String, String>, Int>,
    ): Long {
        val id = insertSession(session)
        insertAttempts(attempts.map { it.copy(sessionId = id) })
        upsertStats(stats)
        confusions.forEach { (k, n) ->
            val existing = confusion(k.first, k.second)?.count ?: 0
            upsertConfusion(ConfusionEntity(k.first, k.second, existing + n))
        }
        return id
    }

    @Query("DELETE FROM item_stats") suspend fun clearStats()
    @Query("DELETE FROM confusion") suspend fun clearConfusion()
    @Query("DELETE FROM session") suspend fun clearSessions()
    @Query("DELETE FROM attempt") suspend fun clearAttempts()

    @Transaction
    suspend fun clearAll() {
        clearStats(); clearConfusion(); clearSessions(); clearAttempts()
    }
}

@Database(
    entities = [ItemStatsEntity::class, ConfusionEntity::class, SessionEntity::class, AttemptEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MorseDatabase : RoomDatabase() {
    abstract fun dao(): MorseDao

    companion object {
        fun create(context: Context): MorseDatabase =
            Room.databaseBuilder(context, MorseDatabase::class.java, "robo-morse.db").build()
    }
}
