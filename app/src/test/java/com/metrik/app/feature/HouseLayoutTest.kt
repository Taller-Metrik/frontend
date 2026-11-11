package com.metrik.app.feature

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.feature.house.HouseLayout
import com.metrik.app.feature.house.RoomPose
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/** Panel interactivo del plano de vivienda: arrastrar ambientes y pegarlos con imán (CU-08). */
class HouseLayoutTest {
    @get:Rule val ev = EvidenceRule()

    private fun rect(w: Double, h: Double) = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))

    @Test
    @Evidencia(
        id = "FU-87", nombre = "Imán: un ambiente soltado cerca de otro se pega a su muro", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que al soltar un ambiente cerca del muro de otro (algo girado y separado) se alinea con ese "
            + "muro, queda al espesor del muro y se registra la unión en el punto donde se soltó.",
        entrada = "Sala 4×3 m quieta; cocina 2×3 m soltada a 0,4 m del muro derecho de la sala, girada 8° y 0,5 m más arriba; espesor 0,15 m",
        esperado = "Se pega al muro derecho de la sala (muro 2) con su muro izquierdo (muro 4), paralela, a 0,15 m; "
            + "los puntos de la unión caen sobre ambos muros; lejos (3 m) no se pega",
    )
    fun imanPegaAlMuro() {
        val sala = rect(4.0, 3.0)
        val cocina = rect(2.0, 3.0)
        val locals = listOf(sala, cocina)
        // Cocina a la derecha de la sala: su muro izquierdo (x = 0 local) a 0,4 m del muro derecho de la sala (x = 4)
        val poses = listOf(RoomPose(), RoomPose(dx = 4.4, dy = 0.5, rotRad = Math.toRadians(8.0)))
        val snap = HouseLayout.snap(1, locals, poses, thicknessM = 0.15)
        assertNotNull(snap)
        snap!!
        val placed = HouseLayout.world(cocina, snap.pose)
        val salaWall = sala[1] to sala[2] // muro 2: x = 4
        val cocinaWall = placed[3] to placed[0] // muro 4: del vértice 4 al 1
        val angA = atan2(salaWall.second.y - salaWall.first.y, salaWall.second.x - salaWall.first.x)
        val angB = atan2(cocinaWall.second.y - cocinaWall.first.y, cocinaWall.second.x - cocinaWall.first.x)
        val gap = (cocinaWall.first.x + cocinaWall.second.x) / 2 - 4.0
        val far = HouseLayout.snap(1, locals, listOf(RoomPose(), RoomPose(dx = 7.0)), thicknessM = 0.15)
        val aPoint = snap.aPointLocal
        val bPointWorld = HouseLayout.toWorld(snap.bPointLocal, cocina, snap.pose)
        ev.obtenido = "muros=${snap.aWall + 1}↔${snap.bWall + 1}, ángulo entre muros=${"%.2f".format(Math.toDegrees(abs(angA - angB)))}°, " +
            "separación=${"%.3f".format(gap)} m, punto A=$aPoint, lejos=${far != null}"
        assertEquals(0, snap.aIndex)
        assertEquals(1, snap.aWall)
        assertEquals(3, snap.bWall)
        assertEquals(180.0, Math.toDegrees(abs(angA - angB)), 0.01)
        assertEquals(0.15, gap, 1e-6)
        assertEquals(4.0, aPoint.x, 1e-6) // sobre el muro derecho de la sala
        assertTrue(aPoint.y in 0.0..3.0)
        assertEquals(4.15, bPointWorld.x, 1e-6) // sobre el muro de la cocina, frente al punto A
        assertEquals(aPoint.y, bPointWorld.y, 1e-6)
        assertNull(far)
    }

    @Test
    @Evidencia(
        id = "FU-88", nombre = "Ambientes iniciales sin encimarse", requisito = "RF-25 (CU-08)",
        objetivo = "Verificar que, al elegir los ambientes, el panel los muestra uno al lado del otro sin superponerse.",
        entrada = "Sala 4×3, pasadizo 1×4 y cocina 2×3 (cada uno con su propio origen en 0;0)",
        esperado = "Ningún par de ambientes se toca (separados ≥ 0,5 m)",
    )
    fun posicionesIniciales() {
        val locals = listOf(rect(4.0, 3.0), rect(1.0, 4.0), rect(2.0, 3.0))
        val poses = HouseLayout.initialPoses(locals)
        val worlds = locals.indices.map { HouseLayout.world(locals[it], poses[it]) }
        fun minX(p: List<Point>) = p.minOf { it.x }
        fun maxX(p: List<Point>) = p.maxOf { it.x }
        val gaps = (0 until worlds.size - 1).map { minX(worlds[it + 1]) - maxX(worlds[it]) }
        ev.obtenido = "separaciones=${gaps.map { "%.2f".format(it) }} m, áreas=${worlds.map { "%.1f".format(Geometry.analyze(it).areaM2) }}"
        assertTrue(gaps.all { it >= 0.5 })
    }
}
