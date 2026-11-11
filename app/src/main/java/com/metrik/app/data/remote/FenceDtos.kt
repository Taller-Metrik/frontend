package com.metrik.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* JSON del cerco perimétrico: POST /surveys/{id}/fence (CU-09, RF-24, RF-27). */

@Serializable
data class GateDto(val side: Int, @SerialName("width_m") val widthM: Double)

@Serializable
data class FenceInDto(
    /** wire = postes con alambre de púas; brick = muro de ladrillo */
    val kind: String,
    val gates: List<GateDto> = emptyList(),
    @SerialName("post_spacing_m") val postSpacingM: Double = 3.0,
    val strands: Int = 4,
    @SerialName("wall_height_m") val wallHeightM: Double = 2.40,
    @SerialName("column_spacing_m") val columnSpacingM: Double = 3.0,
    /** Precio unitario por clave de partida; null = sin presupuesto. */
    val prices: Map<String, Double>? = null,
    @SerialName("overhead_pct") val overheadPct: Double = 10.0,
    @SerialName("profit_pct") val profitPct: Double = 5.0,
)

@Serializable
data class TakeoffItemDto(
    val key: String,
    val description: String,
    val unit: String,
    val quantity: Double,
    val min: Double,
    val max: Double,
    /** Lo que conviene comprar: el máximo del rango, redondeado hacia arriba. */
    val purchase: Double,
    /** false = desglose informativo: no se le pone precio. */
    val priceable: Boolean = true,
)

@Serializable
data class BudgetLineDto(
    val key: String,
    val description: String,
    val unit: String,
    val quantity: Double,
    @SerialName("unit_price") val unitPrice: Double,
    val subtotal: Double,
)

@Serializable
data class BudgetDto(
    val lines: List<BudgetLineDto>,
    @SerialName("direct_cost") val directCost: Double,
    @SerialName("overhead_pct") val overheadPct: Double,
    val overhead: Double,
    @SerialName("profit_pct") val profitPct: Double,
    val profit: Double,
    val subtotal: Double,
    @SerialName("igv_pct") val igvPct: Double,
    val igv: Double,
    val total: Double,
    @SerialName("missing_prices") val missingPrices: List<String>,
    val currency: String = "PEN",
)

/** Partidas que quedaron fuera del presupuesto por no tener precio, con su descripción (o su clave si no se conoce). */
fun BudgetDto.missingLabels(items: List<TakeoffItemDto>): List<String> =
    missingPrices.map { key -> items.firstOrNull { it.key == key }?.description ?: key }

@Serializable
data class FenceOutDto(
    @SerialName("survey_id") val surveyId: Int,
    val kind: String,
    @SerialName("perimeter_m") val perimeterM: Double,
    @SerialName("perimeter_u95_m") val perimeterU95M: Double? = null,
    @SerialName("net_length_m") val netLengthM: Double,
    val items: List<TakeoffItemDto>,
    val assumptions: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val budget: BudgetDto? = null,
)
