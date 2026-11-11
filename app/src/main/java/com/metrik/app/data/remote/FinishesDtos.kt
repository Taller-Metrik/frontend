package com.metrik.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* JSON del metrado de acabados (POST /surveys/{id}/finishes) y del catálogo de precios (/prices). CU-07. */

@Serializable
data class OpeningDto(
    val wall: Int,
    /** door | window */
    val kind: String,
    @SerialName("width_m") val widthM: Double,
    @SerialName("height_m") val heightM: Double,
)

@Serializable
data class FinishesInDto(
    @SerialName("height_m") val heightM: Double,
    val openings: List<OpeningDto> = emptyList(),
    /** Mano de obra por m² o m; los insumos toman el precio del catálogo. */
    @SerialName("labor_prices") val laborPrices: Map<String, Double>? = null,
    @SerialName("use_catalog") val useCatalog: Boolean = true,
    @SerialName("overhead_pct") val overheadPct: Double = 10.0,
    @SerialName("profit_pct") val profitPct: Double = 5.0,
)

@Serializable
data class FinishesOutDto(
    @SerialName("survey_id") val surveyId: Int,
    @SerialName("area_m2") val areaM2: Double,
    @SerialName("perimeter_m") val perimeterM: Double,
    @SerialName("height_m") val heightM: Double,
    val items: List<TakeoffItemDto>,
    val assumptions: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val budget: BudgetDto? = null,
    /** Origen del precio de cada partida: user | store | input */
    @SerialName("price_sources") val priceSources: Map<String, String> = emptyMap(),
)

@Serializable
data class PriceDto(
    val key: String,
    val description: String,
    val unit: String,
    val content: Double,
    @SerialName("content_unit") val contentUnit: String,
    val price: Double? = null,
    /** user | store | none */
    val source: String,
    @SerialName("store_price") val storePrice: Double? = null,
    val store: String? = null,
    @SerialName("product_name") val productName: String? = null,
    val url: String? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val status: String? = null,
)

@Serializable
data class PriceInDto(val price: Double)
