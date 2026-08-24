package dev.racer.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.PI

/**
 * The Ferrari-style Formula 1 car, generated procedurally.
 *
 * The body sits on the ground plane with its origin at the centre of the
 * wheelbase and +Z forward, which is the convention [Vehicle] drives.
 *
 * The wheels come back as separate meshes so the renderer can spin and steer
 * them; everything else is one mesh, and therefore one draw call.
 */
object CarMesh {

    const val FRONT_AXLE = 1.72f
    const val REAR_AXLE = -1.66f
    const val FRONT_TRACK = 0.86f
    const val REAR_TRACK = 0.90f
    const val FRONT_RADIUS = 0.355f
    const val REAR_RADIUS = 0.375f

    private val RED = Material.rgb(0xD40000, specular = 0.85f)
    private val RED_DARK = Material.rgb(0x8F0000, specular = 0.7f)
    private val CARBON = Material.rgb(0x14161A, specular = 0.45f)
    private val MATTE_BLACK = Material.rgb(0x0D0F12, specular = 0.12f)
    private val RUBBER = Material.rgb(0x121316, specular = 0.05f)
    private val CHROME = Material.rgb(0xD8DDE3, specular = 1.0f)
    private val GOLD = Material.rgb(0xD9A441, specular = 0.9f)
    private val WHITE = Material.rgb(0xF2F2F2, specular = 0.4f)
    private val YELLOW = Material.rgb(0xFFD400, specular = 0.5f)

    class Wheel(val mesh: Mesh, val x: Float, val z: Float, val front: Boolean)

    class Car(val body: Mesh, val wheels: List<Wheel>)

    fun build(): Car {
        val b = MeshBuilder()
        monocoque(b)
        frontWing(b)
        sidepods(b)
        floorAndDiffuser(b)
        cockpitAndHalo(b)
        engineCover(b)
        rearWing(b)
        suspension(b)
        livery(b)

        val wheels = listOf(
            Wheel(wheel(FRONT_RADIUS, 0.30f, true), FRONT_TRACK, FRONT_AXLE, true),
            Wheel(wheel(FRONT_RADIUS, 0.30f, true), -FRONT_TRACK, FRONT_AXLE, true),
            Wheel(wheel(REAR_RADIUS, 0.40f, false), REAR_TRACK, REAR_AXLE, false),
            Wheel(wheel(REAR_RADIUS, 0.40f, false), -REAR_TRACK, REAR_AXLE, false)
        )
        return Car(b.build(), wheels)
    }

    /**
     * The survival cell, lofted through cross-sections from the nose tip to the
     * gearbox. Each section is a superellipse, which gives the boxy-but-rounded
     * shape of a real tub far better than a stack of primitives would.
     */
    private fun monocoque(b: MeshBuilder) {
        // z, halfWidth, bottomY, topY
        val sections = listOf(
            floatArrayOf(2.62f, 0.075f, 0.30f, 0.40f),
            floatArrayOf(2.25f, 0.115f, 0.26f, 0.46f),
            floatArrayOf(1.80f, 0.175f, 0.20f, 0.55f),
            floatArrayOf(1.30f, 0.245f, 0.15f, 0.66f),
            floatArrayOf(0.80f, 0.300f, 0.13f, 0.74f),
            floatArrayOf(0.30f, 0.330f, 0.12f, 0.78f),
            floatArrayOf(-0.10f, 0.330f, 0.12f, 0.86f),
            floatArrayOf(-0.55f, 0.310f, 0.13f, 0.94f),
            floatArrayOf(-1.05f, 0.265f, 0.15f, 0.88f),
            floatArrayOf(-1.55f, 0.215f, 0.17f, 0.74f),
            floatArrayOf(-2.00f, 0.150f, 0.19f, 0.60f),
            floatArrayOf(-2.30f, 0.100f, 0.21f, 0.50f)
        )
        val ringSize = 16
        val rings = sections.map { (z, hw, by, ty) ->
            val cy = (by + ty) / 2
            val hh = (ty - by) / 2
            (0 until ringSize).map { i ->
                val a = i.toFloat() / ringSize * 2f * PI.toFloat()
                val c = cos(a); val s = sin(a)
                val p = 2.6f
                val sx = sign(c) * abs(c).pow(2f / p)
                val sy = sign(s) * abs(s).pow(2f / p)
                Vec3(sx * hw, cy + sy * hh, z)
            }
        }
        b.addLoft(rings, RED)

        // Nose tip cap and the front wing pylons.
        b.addSphere(0.078f, 16, Mat4.compose(Vec3(0f, 0.35f, 2.63f), scale = Vec3(1f, 0.85f, 1.4f)), RED)
        for (x in listOf(-0.16f, 0.16f)) {
            b.addRoundedBox(Vec3(0.05f, 0.24f, 0.30f), 0.02f, 2,
                Mat4.compose(Vec3(x, 0.19f, 2.36f), Vec3(0.22f, 0f, 0f)), CARBON)
        }
    }

    /** Main plane plus three flaps stacked up and back, endplates and strakes. */
    private fun frontWing(b: MeshBuilder) {
        val z0 = 2.42f
        fun plane(w: Float, chord: Float, thick: Float, y: Float, z: Float, tilt: Float, mat: Material) {
            b.addRoundedBox(Vec3(w, thick, chord), thick * 0.45f, 2,
                Mat4.compose(Vec3(0f, y, z0 + z), Vec3(tilt, 0f, 0f)), mat)
        }
        plane(1.94f, 0.46f, 0.035f, 0.070f, 0.10f, -0.06f, RED)
        plane(1.90f, 0.26f, 0.032f, 0.140f, -0.09f, -0.26f, RED)
        plane(1.84f, 0.22f, 0.030f, 0.215f, -0.21f, -0.40f, RED_DARK)
        plane(1.76f, 0.18f, 0.028f, 0.290f, -0.31f, -0.54f, CARBON)

        for (s in listOf(-1f, 1f)) {
            // Ride heights here are deliberately just above zero: a real front
            // wing runs millimetres off the road, but anything that dips below
            // it clips through the track surface.
            b.addRoundedBox(Vec3(0.035f, 0.52f, 0.62f), 0.02f, 2,
                Mat4.compose(Vec3(s * 0.98f, 0.28f, z0 - 0.02f), Vec3(0f, s * 0.06f, s * -0.10f)), CARBON)
            b.addRoundedBox(Vec3(0.16f, 0.026f, 0.42f), 0.012f, 1,
                Mat4.compose(Vec3(s * 0.94f, 0.03f, z0 - 0.02f), Vec3(0f, s * 0.08f, 0f)), CARBON)
            for (i in 0 until 3) {
                b.addRoundedBox(Vec3(0.02f, 0.10f, 0.22f), 0.008f, 1,
                    Mat4.compose(Vec3(s * (0.42f + i * 0.16f), 0.06f, z0 - 0.10f), Vec3(0f, s * 0.12f, 0f)),
                    MATTE_BLACK)
            }
        }
    }

    /**
     * Sidepods: a rounded box whose vertices are pulled in toward the rear to
     * give the coke-bottle taper, plus inlets, bargeboards and a winglet.
     */
    private fun sidepods(b: MeshBuilder) {
        for (side in listOf(-1f, 1f)) {
            val origin = Vec3(side * 0.52f, 0.40f, -0.55f)

            // Built as a series of lofted rectangles so the taper is continuous.
            val sections = (0..10).map { i ->
                val t = i / 10f                       // 0 = front, 1 = rear
                val z = 0.90f - t * 1.90f
                val taper = smoothstep(t, 0.0f, 1.0f)
                val hw = 0.35f * (1f - 0.62f * taper)
                val hh = 0.30f * (1f - 0.34f * taper)
                val cy = -0.10f * taper
                (0 until 12).map { j ->
                    val a = j / 12f * 2f * PI.toFloat()
                    val c = cos(a); val s = sin(a)
                    val p = 3.0f
                    Vec3(
                        origin.x + sign(c) * abs(c).pow(2f / p) * hw,
                        origin.y + cy + sign(s) * abs(s).pow(2f / p) * hh,
                        origin.z + z
                    )
                }
            }
            b.addLoft(sections, RED)

            // Inlet mouth.
            b.addRoundedBox(Vec3(0.50f, 0.36f, 0.10f), 0.06f, 2,
                Mat4.compose(origin + Vec3(0.02f, 0.05f, 0.90f)), MATTE_BLACK)
            b.addRoundedBox(Vec3(0.58f, 0.44f, 0.06f), 0.05f, 2,
                Mat4.compose(origin + Vec3(0.02f, 0.05f, 0.96f)), CARBON)

            // Undercut floor edge.
            b.addRoundedBox(Vec3(0.70f, 0.05f, 1.80f), 0.02f, 1,
                Mat4.compose(origin + Vec3(-0.02f, -0.30f, -0.05f), Vec3(0f, 0f, side * 0.05f)), CARBON)

            // Bargeboard cluster ahead of the pod.
            for (i in 0 until 3) {
                b.addRoundedBox(Vec3(0.025f, 0.30f - i * 0.05f, 0.34f), 0.012f, 1,
                    Mat4.compose(
                        origin + Vec3(-0.10f + i * 0.10f, -0.02f - i * 0.02f, 1.18f),
                        Vec3(0f, side * (0.18f + i * 0.06f), side * 0.10f)
                    ), CARBON)
            }

            // Winglet on the pod shoulder.
            b.addRoundedBox(Vec3(0.46f, 0.022f, 0.26f), 0.01f, 1,
                Mat4.compose(origin + Vec3(0.02f, 0.30f, 0.34f), Vec3(-0.12f, 0f, side * 0.18f)), CARBON)
        }
    }

    private fun floorAndDiffuser(b: MeshBuilder) {
        b.addRoundedBox(Vec3(1.52f, 0.06f, 3.55f), 0.03f, 1,
            Mat4.compose(Vec3(0f, 0.10f, -0.25f)), CARBON)
        for (s in listOf(-1f, 1f)) {
            b.addRoundedBox(Vec3(0.10f, 0.05f, 3.3f), 0.02f, 1,
                Mat4.compose(Vec3(s * 0.78f, 0.11f, -0.30f), Vec3(0f, 0f, s * 0.10f)), MATTE_BLACK)
        }
        // Stepped diffuser ramp with vertical strakes.
        val d = Vec3(0f, 0.14f, -2.05f)
        b.addRoundedBox(Vec3(1.34f, 0.05f, 0.80f), 0.02f, 1,
            Mat4.compose(d + Vec3(0f, 0.10f, -0.30f), Vec3(-0.26f, 0f, 0f)), CARBON)
        for (i in -2..2) {
            b.addRoundedBox(Vec3(0.022f, 0.20f, 0.72f), 0.01f, 1,
                Mat4.compose(d + Vec3(i * 0.26f, 0.14f, -0.30f), Vec3(-0.26f, 0f, 0f)), MATTE_BLACK)
        }
    }

    private fun cockpitAndHalo(b: MeshBuilder) {
        b.addRoundedBox(Vec3(0.50f, 0.10f, 0.90f), 0.05f, 2, Mat4.compose(Vec3(0f, 0.855f, 0.10f)), MATTE_BLACK)
        b.addRoundedBox(Vec3(0.52f, 0.22f, 0.26f), 0.09f, 2, Mat4.compose(Vec3(0f, 0.94f, -0.40f)), MATTE_BLACK)

        // Driver's helmet, for a bit of presence in the tub.
        b.addSphere(0.155f, 16, Mat4.compose(Vec3(0f, 0.95f, -0.06f)), WHITE)
        b.addRoundedBox(Vec3(0.20f, 0.06f, 0.05f), 0.02f, 1, Mat4.compose(Vec3(0f, 0.965f, 0.115f)), YELLOW)

        // Halo: central pylon, titanium hoop, and its rear mounts.
        b.addRoundedBox(Vec3(0.07f, 0.20f, 0.10f), 0.03f, 1, Mat4.compose(Vec3(0f, 0.90f, 0.60f)), CHROME)
        val hoop = listOf(
            Vec3(0.00f, 0.99f, 0.66f), Vec3(0.26f, 1.02f, 0.44f), Vec3(0.38f, 1.06f, -0.02f),
            Vec3(0.34f, 1.10f, -0.42f), Vec3(0.20f, 1.12f, -0.60f), Vec3(-0.20f, 1.12f, -0.60f),
            Vec3(-0.34f, 1.10f, -0.42f), Vec3(-0.38f, 1.06f, -0.02f), Vec3(-0.26f, 1.02f, 0.44f)
        )
        b.addTube(resample(hoop, 60, closed = true), 0.035f, 8, CHROME, closed = true)
        for (s in listOf(-1f, 1f)) {
            b.addRoundedBox(Vec3(0.05f, 0.18f, 0.06f), 0.02f, 1, Mat4.compose(Vec3(s * 0.30f, 1.04f, -0.60f)), CHROME)
        }
    }

    private fun engineCover(b: MeshBuilder) {
        // Airbox above the driver.
        b.addCylinder(0.20f, 0.24f, 0.34f, 16,
            Mat4.compose(Vec3(0f, 0.99f, -0.78f), Vec3((PI / 2).toFloat(), 0f, 0f)), RED)
        b.addCylinder(0.145f, 0.16f, 0.20f, 14,
            Mat4.compose(Vec3(0f, 0.99f, -0.66f), Vec3((PI / 2).toFloat(), 0f, 0f)), MATTE_BLACK)

        // Engine cover, tapering to the rear like the sidepods.
        val sections = (0..10).map { i ->
            val t = i / 10f
            val z = -0.70f - t * 1.30f
            val taper = smoothstep(t, 0f, 1f)
            val hw = 0.23f * (1f - 0.55f * taper)
            val hh = 0.26f * (1f - 0.45f * taper)
            val cy = 0.80f - 0.08f * taper
            (0 until 12).map { j ->
                val a = j / 12f * 2f * PI.toFloat()
                val c = cos(a); val s = sin(a)
                Vec3(
                    sign(c) * abs(c).pow(2f / 3f) * hw,
                    cy + sign(s) * abs(s).pow(2f / 3f) * hh,
                    z
                )
            }
        }
        b.addLoft(sections, RED)

        b.addRoundedBox(Vec3(0.03f, 0.20f, 0.70f), 0.01f, 1,
            Mat4.compose(Vec3(0f, 0.86f, -1.62f), Vec3(0.10f, 0f, 0f)), RED_DARK)
        b.addCylinder(0.075f, 0.09f, 0.22f, 14,
            Mat4.compose(Vec3(0f, 0.70f, -2.22f), Vec3((PI / 2).toFloat(), 0f, 0f)), CHROME)
        // Rain light.
        b.addRoundedBox(Vec3(0.10f, 0.10f, 0.05f), 0.02f, 1, Mat4.compose(Vec3(0f, 0.44f, -2.30f)),
            Material.rgb(0xFF2200, specular = 0.2f))
    }

    private fun rearWing(b: MeshBuilder) {
        // Mounted so the top of the endplates lands near 1.15 m, which is about
        // where a real car's rear wing and roll hoop sit.
        val o = Vec3(0f, 0.70f, -2.06f)
        b.addRoundedBox(Vec3(1.05f, 0.045f, 0.42f), 0.02f, 1,
            Mat4.compose(o + Vec3(0f, 0.22f, 0.02f), Vec3(-0.16f, 0f, 0f)), RED)
        b.addRoundedBox(Vec3(1.05f, 0.040f, 0.28f), 0.02f, 1,
            Mat4.compose(o + Vec3(0f, 0.36f, -0.14f), Vec3(-0.55f, 0f, 0f)), CARBON)
        for (s in listOf(-1f, 1f)) {
            b.addRoundedBox(Vec3(0.035f, 0.55f, 0.66f), 0.03f, 2,
                Mat4.compose(o + Vec3(s * 0.535f, 0.20f, -0.02f)), RED)
            b.addRoundedBox(Vec3(0.05f, 0.44f, 0.06f), 0.02f, 1,
                Mat4.compose(o + Vec3(s * 0.32f, -0.02f, -0.10f), Vec3(0.2f, 0f, 0f)), CARBON)
        }
        // Beam wing under the main element.
        b.addRoundedBox(Vec3(0.95f, 0.035f, 0.22f), 0.015f, 1,
            Mat4.compose(o + Vec3(0f, -0.30f, -0.10f), Vec3(-0.30f, 0f, 0f)), CARBON)
    }

    /** Wishbones from the tub out to each hub. */
    private fun suspension(b: MeshBuilder) {
        for (wheel in listOf(
            Triple(FRONT_TRACK, FRONT_AXLE, true), Triple(-FRONT_TRACK, FRONT_AXLE, true),
            Triple(REAR_TRACK, REAR_AXLE, false), Triple(-REAR_TRACK, REAR_AXLE, false)
        )) {
            val (x, z, front) = wheel
            val s = sign(x)
            val hub = Vec3(x, 0.36f, z)
            val inboard = listOf(
                Vec3(s * 0.22f, 0.52f, z + if (front) 0.18f else -0.16f),
                Vec3(s * 0.22f, 0.28f, z + if (front) -0.22f else 0.20f),
                Vec3(s * 0.24f, 0.44f, z + if (front) -0.30f else 0.28f)
            )
            inboard.forEachIndexed { i, from ->
                val to = hub + Vec3(-s * 0.05f, if (i == 1) -0.10f else 0.06f, 0f)
                b.addTube(listOf(from, to), if (i == 2) 0.018f else 0.026f, 6, CARBON)
            }
        }
    }

    private fun livery(b: MeshBuilder) {
        for (s in listOf(-1f, 1f)) {
            b.addBox(Vec3(0.01f, 0.26f, 0.34f), Mat4.compose(Vec3(s * 0.335f, 0.60f, -0.05f)), WHITE)
        }
        b.addBox(Vec3(0.20f, 0.008f, 1.10f), Mat4.compose(Vec3(0f, 0.80f, 0.55f), Vec3(-0.12f, 0f, 0f)), WHITE)
    }

    /**
     * One wheel, centred on its own origin so the renderer can rotate it for
     * wheel spin and steering. Built lying along X, as it sits on the car.
     */
    private fun wheel(radius: Float, width: Float, front: Boolean): Mesh {
        val b = MeshBuilder()
        val acrossX = Mat4.rotationZ((PI / 2).toFloat())

        // Tyre, with the shoulders drawn in slightly so the tread is crowned.
        val treadSections = (0..8).map { i ->
            val t = i / 8f
            val x = (t - 0.5f) * width
            val edge = abs(t - 0.5f) * 2f
            val r = radius * (1f - 0.09f * edge.pow(3f))
            (0 until 20).map { j ->
                val a = j / 20f * 2f * PI.toFloat()
                Vec3(x, sin(a) * r, cos(a) * r)
            }
        }
        b.addLoft(treadSections, RUBBER)

        // Sidewall bands, rim, spokes and the centre lock nut.
        for (s in listOf(-1f, 1f)) {
            b.addTube(
                (0..24).map { j ->
                    val a = j / 24f * 2f * PI.toFloat()
                    Vec3(s * width / 2 * 0.99f, sin(a) * radius * 0.80f, cos(a) * radius * 0.80f)
                }, 0.012f, 6, YELLOW, closed = true
            )
        }
        b.addCylinder(radius * 0.66f, radius * 0.66f, width * 0.96f, 20, acrossX, CHROME)
        for (i in 0 until 8) {
            val a = i / 8f * 2f * PI.toFloat()
            b.addRoundedBox(Vec3(0.05f, radius * 0.60f, 0.05f), 0.02f, 1,
                Mat4.compose(Vec3(0f, sin(a) * radius * 0.33f, cos(a) * radius * 0.33f), Vec3(a, 0f, 0f)) * acrossX,
                MATTE_BLACK)
        }
        b.addCylinder(0.075f, 0.075f, width * 1.02f, 12, acrossX, GOLD)

        // Brake duct on the inboard face.
        b.addDisc(radius * 0.62f, 20,
            Mat4.compose(Vec3(-(width / 2 + 0.03f), 0f, 0f)) * acrossX, CARBON, false)
        return b.build()
    }

    private fun smoothstep(x: Float, edge0: Float, edge1: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Resample a closed polyline into a smooth path, for sweeping the halo. */
    private fun resample(points: List<Vec3>, count: Int, closed: Boolean): List<Vec3> {
        val n = points.size
        fun at(i: Int) = points[((i % n) + n) % n]
        return (0 until count).map { k ->
            val t = k.toFloat() / count * n
            val i = t.toInt()
            val f = t - i
            // Catmull-Rom through the control points.
            val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
            val f2 = f * f; val f3 = f2 * f
            Vec3(
                0.5f * ((2 * p1.x) + (-p0.x + p2.x) * f + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * f2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * f3),
                0.5f * ((2 * p1.y) + (-p0.y + p2.y) * f + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * f2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * f3),
                0.5f * ((2 * p1.z) + (-p0.z + p2.z) * f + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * f2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * f3)
            )
        }
    }
}
