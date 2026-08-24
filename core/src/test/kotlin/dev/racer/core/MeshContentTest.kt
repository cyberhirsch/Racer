package dev.racer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car and track meshes cannot be eyeballed in CI, so instead these tests
 * assert the things a wrong mesh would break: that it exists, is well formed,
 * has believable real-world dimensions, and sits where the physics expects it.
 */
class MeshContentTest {

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

    private fun check(m: Mesh, what: String) {
        assertTrue("$what is empty", m.vertexCount > 0)
        assertEquals("$what index count", 0, m.indexCount % 3)
        for (i in m.indices) assertTrue("$what index out of range", i in 0 until m.vertexCount)
        for (i in 0 until m.vertexCount * Mesh.FLOATS_PER_VERTEX) {
            assertTrue("$what has non-finite data", m.vertices[i].isFinite())
        }
    }

    @Test
    fun `the car is the size of a real F1 car and sits on the ground`() {
        val car = CarMesh.build()
        check(car.body, "car body")
        val (lo, hi) = bounds(car.body)

        val length = hi.z - lo.z
        val width = hi.x - lo.x
        val height = hi.y - lo.y
        println("car: %.2fm long, %.2fm wide, %.2fm tall (%d verts, %d tris)".format(
            length, width, height, car.body.vertexCount, car.body.indexCount / 3))

        // Current F1 cars are about 5.6m long and 2.0m wide, and stand roughly
        // 1.0-1.2m to the top of the rear wing and roll hoop.
        assertTrue("car length ${length}m", length in 4.6f..5.8f)
        assertTrue("car width ${width}m", width in 1.7f..2.1f)
        assertTrue("car height ${height}m", height in 0.9f..1.3f)

        // Nothing may poke through the road surface.
        assertTrue("bodywork dips below the ground (${lo.y}m)", lo.y >= -0.02f)

        // The front wing must be ahead of the front axle, the rear wing behind
        // the rear axle — i.e. the model is the right way round.
        assertTrue("nothing ahead of the front axle", hi.z > CarMesh.FRONT_AXLE)
        assertTrue("nothing behind the rear axle", lo.z < CarMesh.REAR_AXLE)
    }

    @Test
    fun `wheels are the right size and centred on their own origin`() {
        val car = CarMesh.build()
        assertEquals(4, car.wheels.size)
        for (w in car.wheels) {
            check(w.mesh, "wheel")
            val (lo, hi) = bounds(w.mesh)
            val diameter = hi.y - lo.y
            val expected = if (w.front) CarMesh.FRONT_RADIUS * 2 else CarMesh.REAR_RADIUS * 2
            assertEquals("wheel diameter", expected, diameter, 0.03f)

            // Must be centred, or spinning it would make it wobble.
            assertEquals("wheel not centred in Y", 0f, (hi.y + lo.y) / 2, 0.02f)
            assertEquals("wheel not centred in Z", 0f, (hi.z + lo.z) / 2, 0.02f)
            assertEquals("wheel not centred in X", 0f, (hi.x + lo.x) / 2, 0.05f)
        }
        // Rear wheels wider than front, as on a real car.
        val front = bounds(car.wheels.first { it.front }.mesh)
        val rear = bounds(car.wheels.first { !it.front }.mesh)
        assertTrue("rear tyres should be wider than the fronts",
            (rear.second.x - rear.first.x) > (front.second.x - front.first.x))
    }

    @Test
    fun `the wheels are positioned at the axles and inside the car's width`() {
        val car = CarMesh.build()
        val bodyWidth = bounds(car.body).let { it.second.x - it.first.x }
        for (w in car.wheels) {
            assertEquals("wheel not on an axle",
                if (w.front) CarMesh.FRONT_AXLE else CarMesh.REAR_AXLE, w.z, 1e-4f)
            val outerEdge = kotlin.math.abs(w.x) + 0.25f
            assertTrue("wheel sticks out past the bodywork", outerEdge <= bodyWidth / 2 + 0.15f)
        }
    }

    @Test
    fun `the track mesh spans the circuit and its gates sit on the checkpoints`() {
        val track = Track(Levels.config(0))
        val built = TrackMesh.build(track)
        check(built.ground, "track")

        val (lo, hi) = bounds(built.ground)
        println("track mesh: %.0f x %.0f m, %d verts, %d tris".format(
            hi.x - lo.x, hi.z - lo.z, built.ground.vertexCount, built.ground.indexCount / 3))

        // A 1500 m circuit is a few hundred metres across.
        assertTrue("track extent looks wrong", (hi.x - lo.x) in 150f..1200f)

        assertEquals(Track.CHECKPOINT_COUNT, built.gates.size)
        assertEquals(1, built.gates.count { it.finish })
        for (g in built.gates) {
            check(g.mesh, "gate")
            val f = track.frames[g.frameIndex]
            val (glo, ghi) = bounds(g.mesh)
            val cx = (glo.x + ghi.x) / 2
            val cz = (glo.z + ghi.z) / 2
            assertEquals("gate not on its checkpoint", f.pos.x.toFloat(), cx, 1.0f)
            assertEquals("gate not on its checkpoint", f.pos.z.toFloat(), cz, 1.0f)
        }
    }

    @Test
    fun `the whole scene stays within a sane draw-call and triangle budget`() {
        val car = CarMesh.build()
        val track = TrackMesh.build(Track(Levels.config(5)))   // the longest circuit
        val carTris = (car.body.indexCount + car.wheels.sumOf { it.mesh.indexCount }) / 3
        val trackTris = track.ground.indexCount / 3
        println("triangles: car $carTris, track $trackTris, total ${carTris + trackTris}")

        // The renderer draws the track as one call, the car body as one, and
        // one per wheel and gate. Keep the totals within reach of a phone GPU.
        assertTrue("car is too heavy at $carTris triangles", carTris < 60_000)
        assertTrue("track is too heavy at $trackTris triangles", trackTris < 250_000)
    }
}
