package com.metrik.app.data.repository

import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.FloorPlanInDto
import com.metrik.app.data.remote.FloorPlanOutDto

/** Plano de vivienda con varios ambientes: lo arma el backend uniendo los muros compartidos (CU-08). */
interface FloorPlanRepository {
    suspend fun plan(projectId: Int, body: FloorPlanInDto): FloorPlanOutDto
    suspend fun dxf(projectId: Int, body: FloorPlanInDto): ByteArray
}

class DefaultFloorPlanRepository(private val api: ApiClient) : FloorPlanRepository {
    override suspend fun plan(projectId: Int, body: FloorPlanInDto) = api.api().floorPlan(projectId, body)
    override suspend fun dxf(projectId: Int, body: FloorPlanInDto): ByteArray =
        api.api().floorPlanDxf(projectId, body).use { it.bytes() }
}
