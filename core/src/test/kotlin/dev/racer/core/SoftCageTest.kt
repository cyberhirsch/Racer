package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deformable panels, checked as a material rather than against the
 * solver's constants: it should be stiff, it should yield, and what it yields
 * should stay yielded.
 */
class SoftCageTest {

    private fun panel(): Pair<Mesh, SoftCage> {
        val mesh = CarMesh.build().partList.first { it.first == CarMesh.Part.FRONT_WING }.second
        return mesh to SoftCage.around(mesh, mesh.bounds())
    }

    private fun settle(c: SoftCage, seconds: Double = 3.0) {
        var t = 0.0
        while (t < seconds && c.awake) { c.step(1f / 120f); t += 1.0 / 120.0 }
    }

    @Test
    fun `an untouched panel is exactly the shape it was built with`() {
        val (mesh, cage) = panel()
        assertEquals("a cage should not bend a panel just by existing", 0f, cage.damage, 1e-3f)
        assertEquals(mesh.vertices.size, cage.vertices.size)
        assertEquals("nothing has yielded yet", 0, cage.yielded)
    }

    @Test
    fun `a hard blow folds the panel, and it stays folded`() {
        val (_, cage) = panel()
        cage.strike(Vec3(0f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 9f, reach = 0.9f)
        settle(cage)
        val bent = cage.damage
        println("front wing bent %.3f m, %d constraints yielded".format(bent, cage.yielded))
        // Measured with the panel's bodily movement taken out, so this is
        // two centimetres of actual folding rather than of being shoved.
        assertTrue("a 9 m/s blow should visibly fold it, got $bent m", bent > 0.02f)
        assertTrue("the fold should be permanent, not elastic", cage.yielded > 0)

        // Left alone it must not creep back toward the factory shape.
        settle(cage, seconds = 5.0)
        assertTrue("the fold sprang back: $bent m became ${cage.damage} m", cage.damage > bent * 0.7f)
    }

    @Test
    fun `a light knock barely marks it`() {
        val (_, cage) = panel()
        cage.strike(Vec3(0f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 0.4f, reach = 0.9f)
        settle(cage)
        println("a 0.4 m/s knock leaves %.4f m".format(cage.damage))
        assertTrue("a gentle knock should not crumple it: ${cage.damage} m", cage.damage < 0.02f)
    }

    @Test
    fun `harder blows fold it further`() {
        fun bend(impulse: Float): Float {
            val (_, cage) = panel()
            cage.strike(Vec3(0f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse, reach = 0.9f)
            settle(cage)
            return cage.damage
        }
        val soft = bend(3f)
        val hard = bend(14f)
        println("bent %.3f m at 3 m/s, %.3f m at 14 m/s".format(soft, hard))
        assertTrue("a hard blow should fold it further than a soft one ($soft vs $hard)", hard > soft * 1.5f)
    }

    @Test
    fun `damage accumulates when the same corner is hit twice`() {
        val (_, cage) = panel()
        cage.strike(Vec3(0.6f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 6f, reach = 0.8f)
        settle(cage)
        val once = cage.damage
        cage.strike(Vec3(0.6f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 6f, reach = 0.8f)
        settle(cage)
        println("hit once: %.3f m, hit twice: %.3f m".format(once, cage.damage))
        assertTrue("hitting the same corner twice should fold it further", cage.damage > once * 1.15f)
    }

    /**
     * The reason the whole thing is a lattice rather than a field of blows:
     * a hit in one place has to move material somewhere else.
     */
    @Test
    fun `a blow in one place moves material elsewhere on the panel`() {
        val (mesh, cage) = panel()
        cage.strike(Vec3(1.0f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 10f, reach = 0.7f)
        settle(cage)
        var far = 0f
        var i = 0
        while (i < mesh.vertices.size) {
            // The far end of the wing, well outside anything the blow touched.
            if (mesh.vertices[i] < -0.6f) {
                val dx = cage.vertices[i] - mesh.vertices[i]
                val dy = cage.vertices[i + 1] - mesh.vertices[i + 1]
                val dz = cage.vertices[i + 2] - mesh.vertices[i + 2]
                far = maxOf(far, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz))
            }
            i += Mesh.FLOATS_PER_VERTEX
        }
        println("the far end of the wing moved %.4f m".format(far))
        assertTrue("the blow did not carry through the panel at all", far > 0.002f)
    }

    @Test
    fun `the mesh keeps its vertex count and finite, unit-length normals`() {
        val (mesh, cage) = panel()
        cage.strike(Vec3(0f, 0.15f, 3.0f), Vec3(0.3f, -1f, -0.5f), impulse = 20f, reach = 1.2f)
        settle(cage)
        assertEquals(mesh.vertices.size, cage.vertices.size)
        var i = 0
        while (i < cage.vertices.size) {
            for (k in 0 until Mesh.FLOATS_PER_VERTEX) {
                assertTrue("a float came out ${cage.vertices[i + k]}", cage.vertices[i + k].isFinite())
            }
            val l = kotlin.math.sqrt(
                cage.vertices[i + 3] * cage.vertices[i + 3] +
                    cage.vertices[i + 4] * cage.vertices[i + 4] +
                    cage.vertices[i + 5] * cage.vertices[i + 5]
            )
            assertEquals("normal length $l", 1.0, l.toDouble(), 1e-3)
            i += Mesh.FLOATS_PER_VERTEX
        }
    }

    @Test
    fun `a panel left alone goes to sleep and costs nothing`() {
        val (_, cage) = panel()
        cage.strike(Vec3(0f, 0.15f, 3.0f), Vec3(0f, 0f, -1f), impulse = 8f, reach = 0.9f)
        settle(cage, seconds = 6.0)
        assertTrue("the lattice should have stopped solving", !cage.awake)
        val version = cage.shapeVersion
        repeat(600) { cage.step(1f / 120f) }
        assertEquals("a sleeping panel must not rebuild anything", version, cage.shapeVersion)
    }

    @Test
    fun `every part of the car can be caged, and none of them explode`() {
        for ((part, mesh) in CarMesh.build().partList) {
            val cage = SoftCage.around(mesh, mesh.bounds())
            cage.strike(mesh.bounds().centre, Vec3(0f, -1f, 0f), impulse = 25f, reach = 5f)
            settle(cage, seconds = 4.0)
            for (f in cage.vertices) assertTrue("$part produced $f", f.isFinite())
            assertTrue("$part deformed ${cage.damage} m, which is not a panel any more", cage.damage < 2f)
        }
    }
}
