package com.metrik.app.feature.house

import com.metrik.app.core.geometry.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Dónde está un ambiente en el panel: girado [rotRad] alrededor de su centro y desplazado ([dx]; [dy]) metros. */
data class RoomPose(val dx: Double = 0.0, val dy: Double = 0.0, val rotRad: Double = 0.0)

/**
 * Panel interactivo del plano de vivienda (CU-08): los ambientes se arrastran y giran con el dedo y, al soltarlos
 * cerca del muro de otro, se pegan con imán: quedan paralelos, al espesor del muro, en el punto donde se soltaron.
 * La unión se guarda como un punto en cada muro, que es lo que el servidor usa para ubicarlos igual.
 */
object HouseLayout {
    /** Distancia y giro máximos para que el imán actúe. */
    const val SNAP_DISTANCE_M = 0.8
    const val SNAP_ANGLE_DEG = 20.0
    /** Largo mínimo de muro enfrentado para considerarlos unidos. */
    const val MIN_OVERLAP_M = 0.2
    private const val INITIAL_GAP_M = 1.0

    /** Resultado del imán: ambiente fijo [aIndex], muros enfrentados, nueva posición y punto de la unión en cada ambiente. */
    data class Snap(
        val aIndex: Int,
        val aWall: Int,
        val bWall: Int,
        val pose: RoomPose,
        val aPointLocal: Point,
        val bPointLocal: Point,
    )

    fun center(local: List<Point>): Point = Point(local.sumOf { it.x } / local.size, local.sumOf { it.y } / local.size)

    fun toWorld(p: Point, local: List<Point>, pose: RoomPose): Point {
        val c = center(local)
        val cs = cos(pose.rotRad)
        val sn = sin(pose.rotRad)
        val x = p.x - c.x
        val y = p.y - c.y
        return Point(cs * x - sn * y + c.x + pose.dx, sn * x + cs * y + c.y + pose.dy)
    }

    fun toLocal(w: Point, local: List<Point>, pose: RoomPose): Point {
        val c = center(local)
        val cs = cos(pose.rotRad)
        val sn = sin(pose.rotRad)
        val x = w.x - c.x - pose.dx
        val y = w.y - c.y - pose.dy
        return Point(cs * x + sn * y + c.x, -sn * x + cs * y + c.y)
    }

    fun world(local: List<Point>, pose: RoomPose): List<Point> = local.map { toWorld(it, local, pose) }

    /** Ambientes uno al lado del otro (de izquierda a derecha), con 1 m entre ellos y apoyados en y = 0. */
    fun initialPoses(locals: List<List<Point>>): List<RoomPose> {
        var cursor = 0.0
        return locals.map { v ->
            // Enderezado (muros horizontales y verticales), luego a la derecha del anterior
            val straight = straighten(v, RoomPose())
            val w = world(v, straight)
            val pose = straight.copy(dx = cursor - w.minOf { it.x }, dy = -w.minOf { it.y })
            cursor += w.maxOf { it.x } - w.minOf { it.x } + INITIAL_GAP_M
            pose
        }
    }

    /**
     * Endereza el ambiente: lo gira lo mínimo (± 45°) para que su muro más largo quede horizontal o vertical.
     * Un recorrido no empieza alineado al norte, así que el ambiente medido suele quedar en diagonal; enderezado,
     * con giros de 90° calza con los demás.
     */
    fun straighten(local: List<Point>, pose: RoomPose): RoomPose {
        val w = world(local, pose)
        val longest = w.indices.maxByOrNull { i -> val a = w[i]; val b = w[(i + 1) % w.size]; hypot(b.x - a.x, b.y - a.y) }
            ?: return pose
        val a = w[longest]
        val b = w[(longest + 1) % w.size]
        val ang = atan2(b.y - a.y, b.x - a.x)
        val quarter = PI / 2
        var off = ang % quarter
        if (off > quarter / 2) off -= quarter
        if (off < -quarter / 2) off += quarter
        return pose.copy(rotRad = pose.rotRad - off)
    }

    /** true si el punto está dentro del ambiente (para saber qué ambiente toca el dedo). */
    fun contains(world: List<Point>, p: Point): Boolean {
        var inside = false
        var j = world.lastIndex
        for (i in world.indices) {
            val a = world[i]
            val b = world[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Imán: busca el muro de otro ambiente más cercano al ambiente [moving] (casi paralelo, enfrentado y a menos de
     * [SNAP_DISTANCE_M]) y lo pega a él. Null si no hay ninguno cerca.
     */
    fun snap(moving: Int, locals: List<List<Point>>, poses: List<RoomPose>, thicknessM: Double): Snap? {
        val bLocal = locals[moving]
        val bWorld = world(bLocal, poses[moving])
        var best: Pair<Double, Snap>? = null
        for (a in locals.indices) {
            if (a == moving) continue
            val aWorld = world(locals[a], poses[a])
            val orientation = signedArea(aWorld)
            for (i in aWorld.indices) {
                val a0 = aWorld[i]
                val a1 = aWorld[(i + 1) % aWorld.size]
                val la = hypot(a1.x - a0.x, a1.y - a0.y)
                if (la < 1e-6) continue
                val ux = (a1.x - a0.x) / la
                val uy = (a1.y - a0.y) / la
                // Normal hacia afuera de A (según el sentido de sus vértices)
                val nx = if (orientation > 0) uy else -uy
                val ny = if (orientation > 0) -ux else ux
                for (j in bWorld.indices) {
                    val b0 = bWorld[j]
                    val b1 = bWorld[(j + 1) % bWorld.size]
                    val lb = hypot(b1.x - b0.x, b1.y - b0.y)
                    if (lb < 1e-6) continue
                    // Muros enfrentados: el de B va en sentido contrario al de A
                    val angA = atan2(uy, ux)
                    val angB = atan2(b1.y - b0.y, b1.x - b0.x)
                    val delta = normalize(angA + PI - angB)
                    if (Math.toDegrees(abs(delta)) > SNAP_ANGLE_DEG) continue
                    val mb = Point((b0.x + b1.x) / 2, (b0.y + b1.y) / 2)
                    val perp = (mb.x - a0.x) * nx + (mb.y - a0.y) * ny
                    if (perp < -SNAP_DISTANCE_M / 2 || perp > SNAP_DISTANCE_M + thicknessM) continue
                    // B debe quedar del lado de afuera de A
                    val cb = center(bWorld)
                    if ((cb.x - a0.x) * nx + (cb.y - a0.y) * ny <= 0) continue
                    val candidate = place(moving, j, a, i, locals, poses, delta, thicknessM, a0, ux, uy, nx, ny, la) ?: continue
                    val score = abs(perp - thicknessM) + 0.02 * Math.toDegrees(abs(delta))
                    if (best == null || score < best.first) best = score to candidate
                }
            }
        }
        return best?.second
    }

    private fun place(
        moving: Int, bWall: Int, a: Int, aWall: Int, locals: List<List<Point>>, poses: List<RoomPose>, delta: Double,
        thicknessM: Double, a0: Point, ux: Double, uy: Double, nx: Double, ny: Double, la: Double,
    ): Snap? {
        val bLocal = locals[moving]
        // 1) Girar B (alrededor de su centro) hasta que su muro quede exactamente enfrentado al de A
        val rotated = poses[moving].copy(rotRad = poses[moving].rotRad + delta)
        var w = world(bLocal, rotated)
        val b0 = w[bWall]
        val b1 = w[(bWall + 1) % w.size]
        // 2) Llevar su muro a la línea del muro de A, al espesor, sin moverlo a lo largo del muro
        val mb = Point((b0.x + b1.x) / 2, (b0.y + b1.y) / 2)
        val perp = (mb.x - a0.x) * nx + (mb.y - a0.y) * ny
        val shift = thicknessM - perp
        val pose = rotated.copy(dx = rotated.dx + nx * shift, dy = rotated.dy + ny * shift)
        w = world(bLocal, pose)
        // 3) Tramo enfrentado y su punto medio (la unión)
        val s1 = (w[bWall].x - a0.x) * ux + (w[bWall].y - a0.y) * uy
        val s2 = (w[(bWall + 1) % w.size].x - a0.x) * ux + (w[(bWall + 1) % w.size].y - a0.y) * uy
        val lo = maxOf(0.0, minOf(s1, s2))
        val hi = minOf(la, maxOf(s1, s2))
        if (hi - lo < MIN_OVERLAP_M) return null
        val s = (lo + hi) / 2
        val aPointWorld = Point(a0.x + ux * s, a0.y + uy * s)
        val bPointWorld = Point(aPointWorld.x + nx * thicknessM, aPointWorld.y + ny * thicknessM)
        return Snap(
            aIndex = a, aWall = aWall, bWall = bWall, pose = pose,
            aPointLocal = toLocal(aPointWorld, locals[a], poses[a]),
            bPointLocal = toLocal(bPointWorld, bLocal, pose),
        )
    }

    private fun signedArea(v: List<Point>): Double =
        v.indices.sumOf { i -> val p = v[i]; val q = v[(i + 1) % v.size]; p.x * q.y - q.x * p.y } / 2

    private fun normalize(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}
