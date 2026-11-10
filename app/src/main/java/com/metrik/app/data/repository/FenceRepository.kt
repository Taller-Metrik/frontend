package com.metrik.app.data.repository

import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.FenceInDto
import com.metrik.app.data.remote.FenceOutDto

/** Metrado y presupuesto del cerco perimétrico: lo calcula el backend (CU-09). */
interface FenceRepository {
    suspend fun calculate(remoteId: Int, body: FenceInDto): FenceOutDto
}

class DefaultFenceRepository(private val api: ApiClient) : FenceRepository {
    override suspend fun calculate(remoteId: Int, body: FenceInDto): FenceOutDto = api.api().fence(remoteId, body)
}
