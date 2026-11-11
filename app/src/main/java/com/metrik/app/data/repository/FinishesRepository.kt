package com.metrik.app.data.repository

import com.metrik.app.data.remote.ApiClient
import com.metrik.app.data.remote.FinishesInDto
import com.metrik.app.data.remote.FinishesOutDto
import com.metrik.app.data.remote.PriceDto
import com.metrik.app.data.remote.PriceInDto

/** Metrado de acabados y catálogo de precios: los calcula y guarda el backend (CU-07, RF-22 a RF-24). */
interface FinishesRepository {
    suspend fun finishes(remoteId: Int, body: FinishesInDto): FinishesOutDto
    suspend fun prices(): List<PriceDto>
    suspend fun setPrice(key: String, price: Double)
    suspend fun resetPrice(key: String)
}

class DefaultFinishesRepository(private val api: ApiClient) : FinishesRepository {
    override suspend fun finishes(remoteId: Int, body: FinishesInDto) = api.api().finishes(remoteId, body)
    override suspend fun prices() = api.api().prices()
    override suspend fun setPrice(key: String, price: Double) { api.api().setPrice(key, PriceInDto(price)) }
    override suspend fun resetPrice(key: String) {
        val r = api.api().resetPrice(key)
        if (!r.isSuccessful) error("No se pudo volver al precio de la tienda (HTTP ${r.code()})")
    }
}
