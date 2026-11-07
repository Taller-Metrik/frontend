package com.metrik.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.MetrikJson
import com.metrik.app.domain.CaptureInfo
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Entity(tableName = "surveys")
data class SurveyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Int,
    val name: String,
    val mode: String,
    val pointsJson: String,
    val cornersJson: String?,
    val gpsJson: String?,
    val captureJson: String?,
    val verticesJson: String,
    val areaM2: Double,
    val perimeterM: Double,
    val scaleDenominator: Int,
    val closed: Boolean,
    val remoteId: Int?,
    val createdAt: Long,
)

@Dao
interface SurveyDao {
    @Insert suspend fun insert(entity: SurveyEntity): Long
    @Update suspend fun update(entity: SurveyEntity)
    @Query("SELECT * FROM surveys WHERE id = :id") suspend fun get(id: Long): SurveyEntity?

    @Query("SELECT * FROM surveys WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeByProject(projectId: Int): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE remoteId IS NULL ORDER BY createdAt") suspend fun pending(): List<SurveyEntity>
    @Query("DELETE FROM surveys WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [SurveyEntity::class], version = 1, exportSchema = false)
abstract class MetrikDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao

    companion object {
        fun create(context: Context): MetrikDatabase =
            Room.databaseBuilder(context, MetrikDatabase::class.java, "metrik.db").build()

        fun inMemory(context: Context): MetrikDatabase =
            Room.inMemoryDatabaseBuilder(context, MetrikDatabase::class.java).allowMainThreadQueries().build()
    }
}

// ---------------------------------------------------------------- mapeo entidad <-> dominio

private val pointList = ListSerializer(ListSerializer(Double.serializer()))
private fun List<Point>.toJson() = MetrikJson.encodeToString(pointList, map { listOf(it.x, it.y) })
private fun String.toPoints() = MetrikJson.decodeFromString(pointList, this).map { Point(it[0], it[1]) }

fun LocalSurvey.toEntity() = SurveyEntity(
    id = id,
    projectId = projectId,
    name = name,
    mode = mode.api,
    pointsJson = points.toJson(),
    cornersJson = corners?.toJson(),
    gpsJson = gps?.let { MetrikJson.encodeToString(ListSerializer(GpsSample.serializer()), it) },
    captureJson = capture?.let { MetrikJson.encodeToString(CaptureInfo.serializer(), it) },
    verticesJson = vertices.toJson(),
    areaM2 = areaM2,
    perimeterM = perimeterM,
    scaleDenominator = scaleDenominator,
    closed = closed,
    remoteId = remoteId,
    createdAt = createdAt,
)

fun SurveyEntity.toDomain() = LocalSurvey(
    id = id,
    projectId = projectId,
    name = name,
    mode = SurveyMode.entries.first { it.api == mode },
    points = pointsJson.toPoints(),
    corners = cornersJson?.toPoints(),
    gps = gpsJson?.let { MetrikJson.decodeFromString(ListSerializer(GpsSample.serializer()), it) },
    capture = captureJson?.let { MetrikJson.decodeFromString(CaptureInfo.serializer(), it) },
    vertices = verticesJson.toPoints(),
    areaM2 = areaM2,
    perimeterM = perimeterM,
    scaleDenominator = scaleDenominator,
    closed = closed,
    remoteId = remoteId,
    createdAt = createdAt,
)
