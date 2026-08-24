package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryTest {

    private fun bounds(m: Mesh): Pair<Vec3, Vec3> {
        var lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until m.vertexCount) {
            val o = i * Mesh.FLOATS_PER_VERTEX
            lo = Vec3(minOf(lo.x, m.vertices[o]), minOf(lo.y, m.vertices[o + 1]), minOf(lo.z, m.vertices[o + 2]))
            hi = Vec3(maxOf(hi.x, m.vertices[o]), maxOf(hi.y, m.vertices[o + 1]), maxOf(hi.z, m.vertices[o + 2]))
        }
        return lo to hi
    }

    private fun assertWellFormed(m: Mesh, what: String) {
        assertTrue("$what has no geometry", m.vertexCount > 0 && m.indexCount > 0)
        assertEquals("$what index count must be a multiple of 3", 0, m.indexCount % 3)
        for (i in m.indices) {
            assertTrue("$what index $i out of range", i in 0 until m.vertexCount)
        }
        for (i in 0 until m.vertexCount) {
            val o = i * Mesh.FLOATS_PER_VERTEX
            for (k in 0 until Mesh.FLOATS_PER_VERTEX) {
                assertTrue("$what has a non-finite vertex component", m.vertices[o + k].isFinite())
            }
            val n = Vec3(m.vertices[o + 3], m.vertices[o + 4], m.vertices[o + 5])
            assertEquals("$what has a non-unit normal", 1.0, n.length().toDouble(), 1e-3)
        }
    }

    @Test
    fun `matrix multiplication and transforms behave`() {
        val id = Mat4.identity()
        val p = Vec3(1f, 2f, 3f)
        assertEquals(p.x, id.transformPoint(p).x, 1e-6f)

        val t = Mat4.translation(5f, 0f, 0f)
        assertEquals(6f, t.transformPoint(p).x, 1e-6f)
        // Directions must ignore translation.
        assertEquals(1f, t.transformDirection(p).x, 1e-6f)

        // Rotating 90 degrees about Y takes +Z to +X.
        val r = Mat4.rotationY((Math.PI / 2).toFloat())
        val v = r.transformPoint(Vec3(0f, 0f, 1f))
        assertEquals(1f, v.x, 1e-5f)
        assertEquals(0f, v.z, 1e-5f)

        // compose() must apply scale first, then rotation, then translation.
        val c = Mat4.compose(Vec3(10f, 0f, 0f), Vec3(0f, (Math.PI / 2).toFloat(), 0f), Vec3(2f, 2f, 2f))
        val q = c.transformPoint(Vec3(0f, 0f, 1f))
        assertEquals(12f, q.x, 1e-4f)
    }

    @Test
    fun `a box has the size it was asked for`() {
        val b = MeshBuilder()
        b.addBox(Vec3(2f, 4f, 6f), Mat4.identity(), Material.rgb(0xff0000))
        val m = b.build()
        assertWellFormed(m, "box")
        val (lo, hi) = bounds(m)
        assertEquals(-1f, lo.x, 1e-5f); assertEquals(1f, hi.x, 1e-5f)
        assertEquals(-2f, lo.y, 1e-5f); assertEquals(2f, hi.y, 1e-5f)
        assertEquals(-3f, lo.z, 1e-5f); assertEquals(3f, hi.z, 1e-5f)
    }

    /**
     * The rounded box is built by clamping onto an inner box and offsetting by
     * the radius, so it must stay exactly within the requested size and must
     * genuinely round the corners.
     */
    @Test
    fun `a rounded box fits its size and rounds its corners`() {
        val size = Vec3(2f, 2f, 2f)
        val b = MeshBuilder()
        b.addRoundedBox(size, 0.4f, 4, Mat4.identity(), Material.rgb(0x00ff00))
        val m = b.build()
        assertWellFormed(m, "rounded box")

        val (lo, hi) = bounds(m)
        assertEquals(-1f, lo.x, 1e-4f); assertEquals(1f, hi.x, 1e-4f)
        assertEquals(-1f, lo.y, 1e-4f); assertEquals(1f, hi.y, 1e-4f)
        assertEquals(-1f, lo.z, 1e-4f); assertEquals(1f, hi.z, 1e-4f)

        // No vertex may sit at the sharp corner of the original box.
        val sharpCorner = Vec3(1f, 1f, 1f)
        var closest = Float.MAX_VALUE
        for (i in 0 until m.vertexCount) {
            val o = i * Mesh.FLOATS_PER_VERTEX
            val p = Vec3(m.vertices[o], m.vertices[o + 1], m.vertices[o + 2])
            closest = minOf(closest, (p - sharpCorner).length())
        }
        assertTrue("corners were not rounded (closest vertex ${closest} from the sharp corner)",
            closest > 0.1f)
    }

    @Test
    fun `primitives are all well formed`() {
        val mat = Material.rgb(0x808080)
        val cases = mapOf(
            "cylinder" to MeshBuilder().apply { addCylinder(1f, 0.5f, 3f, 16, Mat4.identity(), mat) },
            "sphere" to MeshBuilder().apply { addSphere(2f, 16, Mat4.identity(), mat) },
            "disc" to MeshBuilder().apply { addDisc(1.5f, 12, Mat4.identity(), mat, true) },
            "tube" to MeshBuilder().apply {
                addTube((0..8).map { Vec3(it.toFloat(), kotlin.math.sin(it.toFloat()), 0f) }, 0.2f, 8, mat)
            },
            "loft" to MeshBuilder().apply {
                addLoft((0..5).map { s ->
                    (0 until 8).map { i ->
                        val a = i / 8f * 2f * Math.PI.toFloat()
                        Vec3(kotlin.math.cos(a) * (1f + s), kotlin.math.sin(a) * (1f + s), s.toFloat())
                    }
                }, mat)
            }
        )
        for ((name, builder) in cases) assertWellFormed(builder.build(), name)
    }

    @Test
    fun `a cylinder has the right radius and height`() {
        val b = MeshBuilder()
        b.addCylinder(1f, 1f, 4f, 24, Mat4.identity(), Material.rgb(0xffffff))
        val (lo, hi) = bounds(b.build())
        assertEquals(-2f, lo.y, 1e-5f); assertEquals(2f, hi.y, 1e-5f)
        assertEquals(1f, hi.x, 1e-2f)
        assertEquals(1f, hi.z, 1e-2f)
    }

    @Test
    fun `builders accumulate into one mesh`() {
        val b = MeshBuilder()
        b.addBox(Vec3(1f, 1f, 1f), Mat4.identity(), Material.rgb(0xff0000))
        val afterOne = b.vertexCount
        b.addBox(Vec3(1f, 1f, 1f), Mat4.translation(5f, 0f, 0f), Material.rgb(0x00ff00))
        assertEquals(afterOne * 2, b.vertexCount)
        assertWellFormed(b.build(), "combined")
    }
}
