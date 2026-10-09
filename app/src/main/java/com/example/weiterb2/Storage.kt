package com.example.weiterb2

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

@Entity(tableName = "progress")
data class TrackProgress(
    @PrimaryKey val trackId: String,
    val positionMs: Long = 0,
    val completed: Boolean = false,
    val note: String = "",
    val lastPlayed: Long = 0,
)
@Entity(tableName = "bookmarks", indices = [Index("trackId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val positionMs: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
@Dao
abstract class StudyDao {
    @Query("SELECT * FROM progress") abstract fun progress(): Flow<List<TrackProgress>>
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") abstract fun bookmarks(): Flow<List<Bookmark>>
    @Query("SELECT * FROM progress WHERE trackId = :id") abstract suspend fun getProgress(id: String): TrackProgress?
    @Query("SELECT * FROM progress WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 1") abstract suspend fun latest(): TrackProgress?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun ensure(progress: TrackProgress)
    @Query("UPDATE progress SET positionMs = :position, lastPlayed = :time WHERE trackId = :id") protected abstract suspend fun updatePosition(id: String, position: Long, time: Long)
    @Query("UPDATE progress SET completed = :value WHERE trackId = :id") protected abstract suspend fun updateCompleted(id: String, value: Boolean)
    @Query("UPDATE progress SET note = :value WHERE trackId = :id") protected abstract suspend fun updateNote(id: String, value: String)
    @Transaction open suspend fun savePosition(id: String, position: Long) { ensure(TrackProgress(id)); updatePosition(id, position.coerceAtLeast(0), System.currentTimeMillis()) }
    @Transaction open suspend fun setCompleted(id: String, value: Boolean) { ensure(TrackProgress(id)); updateCompleted(id, value) }
    @Transaction open suspend fun setNote(id: String, value: String) { ensure(TrackProgress(id)); updateNote(id, value) }
    @Insert abstract suspend fun addBookmark(bookmark: Bookmark): Long
    @Query("DELETE FROM bookmarks WHERE id = :id") abstract suspend fun deleteBookmark(id: Long)
    @Query("UPDATE bookmarks SET note = :note WHERE id = :id") abstract suspend fun editBookmark(id: Long, note: String)
}
@Database(entities = [TrackProgress::class, Bookmark::class], version = 1, exportSchema = true)
abstract class StudyDatabase : RoomDatabase() { abstract fun study(): StudyDao }

private val Context.settingsStore by preferencesDataStore("playback_settings")
data class PlaybackSettings(val speed: Float = 1f, val continuous: Boolean = false, val repeatTrack: Boolean = false)
class SettingsRepository(private val context: Context) {
    private val speed = floatPreferencesKey("speed")
    private val continuous = booleanPreferencesKey("continuous")
    private val repeat = booleanPreferencesKey("repeat_track")
    val values: Flow<PlaybackSettings> = context.settingsStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        PlaybackSettings((it[speed] ?: 1f).coerceIn(0.5f, 1.5f), it[continuous] ?: false, it[repeat] ?: false)
    }
    suspend fun setSpeed(value: Float) { context.settingsStore.edit { it[speed] = value.coerceIn(0.5f, 1.5f) } }
    suspend fun setContinuous(value: Boolean) { context.settingsStore.edit { it[continuous] = value } }
    suspend fun setRepeat(value: Boolean) { context.settingsStore.edit { it[repeat] = value } }
}
class WeiterApplication : Application() {
    val catalog by lazy { Catalog.load(this) }
    val database by lazy { Room.databaseBuilder(this, StudyDatabase::class.java, "study.db").build() }
    val settings by lazy { SettingsRepository(this) }
}
