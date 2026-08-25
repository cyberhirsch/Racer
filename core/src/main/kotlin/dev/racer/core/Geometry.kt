package dev.racer.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal 3D maths and mesh building.
 *
 * Meshes are built here, in the pure-Kotlin module, rather than in the Android
 * renderer: it keeps all the geometry testable, and the renderer's only job
 * becomes uploading float arrays to the GPU.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    constructor(x: Double, y: Double, z: Double) : this(x.toFloat(), y.toFloat(), z.toFloat())

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) Vec3(0f, 1f, 0f) else Vec3(x / l, y / l, z / l)
    }
}

/**
 * Column-major 4x4 matrix, matching OpenGL's layout so it can be handed
 * straight to glUniformMatrix4fv.
 */
class Mat4(val m: FloatArray = identityValues()) {

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transformPoint(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]
    )

    /** Directions ignore translation. Fine here because we never shear. */
    fun transformDirection(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z
    )

    companion object {
        fun identityValues() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun identity() = Mat4()

        fun translation(x: Float, y: Float, z: Float) = Mat4(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)
        )

        fun scale(x: Float, y: Float, z: Float) = Mat4(
            floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f)
        )

        fun rotationX(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationY(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f))
        }

        fun rotationZ(a: Float): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        }

        /** Translation * rotation (Y, then X, then Z) * scale, applied in that order. */
        fun compose(
            position: Vec3 = Vec3(0f, 0f, 0f),
            rotation: Vec3 = Vec3(0f, 0f, 0f),
            scale: Vec3 = Vec3(1f, 1f, 1f)
        ): Mat4 = translation(position.x, position.y, position.z) *
                rotationY(rotation.y) * rotationX(rotation.x) * rotationZ(rotation.z) *
                scale(scale.x, scale.y, scale.z)

        fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / kotlin.math.tan(fovYRadians / 2f)
            val r = FloatArray(16)
            r[0] = f / aspect
            r[5] = f
            r[10] = (far + near) / (near - far)
            r[11] = -1f
            r[14] = 2f * far * near / (near - far)
            return Mat4(r)
        }

        /**
         * View matrix with the camera rolled about its own view axis.
         *
         * Used to cancel the phone's rotation so the horizon stays level. Kept
         * here rather than in the renderer because a roll sign is very easy to
         * get backwards, and here it can be tested.
         */
        fun lookAtRolled(eye: Vec3, target: Vec3, worldUp: Vec3, rollRadians: Float): Mat4 {
            val forward = (target - eye).normalized()
            var right = forward.cross(worldUp)
            if (right.length() < 1e-4f) right = forward.cross(Vec3(1f, 0f, 0f))
            right = right.normalized()
            val up = right.cross(forward).normalized()
            val rolled = up * cos(rollRadians) + right * sin(rollRadians)
            return lookAt(eye, target, rolled)
        }

        fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            val f = (target - eye).normalized()
            val s = f.cross(up).normalized()
            val u = s.cross(f)
            return Mat4(floatArrayOf(
                s.x, u.x, -f.x, 0f,
                s.y, u.y, -f.y, 0f,
                s.z, u.z, -f.z, 0f,
                -s.dot(eye), -u.dot(eye), f.dot(eye), 1f
            ))
        }
    }
}

/**
 * A renderable mesh: interleaved position(3), normal(3), colour(3) and a
 * specular strength(1), plus an index buffer. Ten floats per vertex.
 */
class Mesh(val vertices: FloatArray, val indices: IntArray) {
    val vertexCount get() = vertices.size / FLOATS_PER_VERTEX
    val indexCount get() = indices.size

    companion object {
        const val FLOATS_PER_VERTEX = 10
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
    }
}

/** A surface appearance. Kept deliberately small — the shader is simple. */
data class Material(val r: Float, val g: Float, val b: Float, val specular: Float = 0.3f) {
    companion object {
        fun rgb(hex: Int, specular: Float = 0.3f) = Material(
            ((hex shr 16) and 0xFF) / 255f,
            ((hex shr 8) and 0xFF) / 255f,
            (hex and 0xFF) / 255f,
            specular
        )
    }
}

/**
 * Accumulates primitives into one mesh.
 *
 * Everything static in a scene goes into a single builder so it becomes one
 * draw call. Left as individual objects, the track's kerbs and barriers alone
 * would be well over a thousand draws per frame.
 */
class MeshBuilder {
    private val verts = ArrayList<Float>(1 shl 16)
    private val idx = ArrayList<Int>(1 shl 16)

    val vertexCount get() = verts.size / Mesh.FLOATS_PER_VERTEX

    fun vertex(p: Vec3, n: Vec3, mat: Material) {
        verts.add(p.x); verts.add(p.y); verts.add(p.z)
        verts.add(n.x); verts.add(n.y); verts.add(n.z)
        verts.add(mat.r); verts.add(mat.g); verts.add(mat.b); verts.add(mat.specular)
    }

    fun triangle(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }

    fun quad(a: Int, b: Int, c: Int, d: Int) { triangle(a, b, c); triangle(a, c, d) }

    /** A flat quad from four corners, wound counter-clockwise when seen from the front. */
    fun addQuad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, mat: Material) {
        val n = (b - a).cross(c - a).normalized()
        val base = vertexCount
        vertex(a, n, mat); vertex(b, n, mat); vertex(c, n, mat); vertex(d, n, mat)
        quad(base, base + 1, base + 2, base + 3)
    }

    /**
     * A box of the given size, transformed. Each face gets its own vertices so
     * the normals stay flat.
     */
    fun addBox(size: Vec3, transform: Mat4, mat: Material) {
        val hx = size.x / 2; val hy = size.y / 2; val hz = size.z / 2
        val c = arrayOf(
            Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz),
            Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz)
        ).map { transform.transformPoint(it) }

        // front, back, right, left, top, bottom
        addQuad(c[4], c[5], c[6], c[7], mat)
        addQuad(c[1], c[0], c[3], c[2], mat)
        addQuad(c[5], c[1], c[2], c[6], mat)
        addQuad(c[0], c[4], c[7], c[3], mat)
        addQuad(c[7], c[6], c[2], c[3], mat)
        addQuad(c[0], c[1], c[5], c[4], mat)
    }

    /**
     * A box with rounded edges and corners.
     *
     * Built exactly rather than approximately: take a point q on the surface of
     * a box of size (inner + radius), clamp it into the inner box to find the
     * nearest inner point c, and the surface point is c + radius * normalize(q - c).
     * That yields flat faces, cylindrical edges and spherical corners, and the
     * normal falls out as normalize(q - c) for free.
     *
     * This soft, moulded look is most of what makes the car read as a car;
     * plain boxes read as Lego.
     */
    fun addRoundedBox(size: Vec3, radius: Float, segments: Int, transform: Mat4, mat: Material) {
        val r = minOf(radius, size.x / 2, size.y / 2, size.z / 2).coerceAtLeast(0f)
        val inner = Vec3(size.x / 2 - r, size.y / 2 - r, size.z / 2 - r)
        val outer = Vec3(size.x / 2, size.y / 2, size.z / 2)
        val n = maxOf(1, segments)

        // The six faces of the outer box, each as a parameterised grid.
        val faces = listOf(
            Triple(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f)),   // +X
            Triple(Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)),   // -X
            Triple(Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f)),    // +Y
            Triple(Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f)),  // -Y
            Triple(Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f)),    // +Z
            Triple(Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f), Vec3(-1f, 0f, 0f))   // -Z
        )

        fun clampToInner(v: Vec3) = Vec3(
            v.x.coerceIn(-inner.x, inner.x),
            v.y.coerceIn(-inner.y, inner.y),
            v.z.coerceIn(-inner.z, inner.z)
        )

        for ((axis, uAxis, vAxis) in faces) {
            val base = vertexCount
            val centre = Vec3(axis.x * outer.x, axis.y * outer.y, axis.z * outer.z)
            for (i in 0..n) for (j in 0..n) {
                val u = (i.toFloat() / n) * 2f - 1f
                val vv = (j.toFloat() / n) * 2f - 1f
                val q = centre +
                        Vec3(uAxis.x * outer.x, uAxis.y * outer.y, uAxis.z * outer.z) * u +
                        Vec3(vAxis.x * outer.x, vAxis.y * outer.y, vAxis.z * outer.z) * vv
                val c = clampToInner(q)
                val dir = if (r > 1e-6f) (q - c).normalized() else axis
                val p = c + dir * r
                vertex(transform.transformPoint(p), transform.transformDirection(dir).normalized(), mat)
            }
            for (i in 0 until n) for (j in 0 until n) {
                val a = base + i * (n + 1) + j
                val b = base + (i + 1) * (n + 1) + j
                quad(a, b, b + 1, a + 1)
            }
        }
    }

    /** A cylinder along the Y axis, with caps. */
    fun addCylinder(
        radiusTop: Float, radiusBottom: Float, height: Float, segments: Int,
        transform: Mat4, mat: Material, caps: Boolean = true
    ) {
        val hy = height / 2
        val base = vertexCount
        for (i in 0..segments) {
            val a = i.toFloat() / segments * 2f * Math.PI.toFloat()
            val ca = cos(a); val sa = sin(a)
            val nrm = transform.transformDirection(Vec3(ca, 0f, sa)).normalized()
            vertex(transform.transformPoint(Vec3(ca * radiusTop, hy, sa * radiusTop)), nrm, mat)
            vertex(transform.transformPoint(Vec3(ca * radiusBottom, -hy, sa * radiusBottom)), nrm, mat)
        }
        for (i in 0 until segments) {
            val a = base + i * 2
            quad(a, a + 1, a + 3, a + 2)
        }
        if (caps) {
            addDisc(radiusTop, segments, transform * Mat4.translation(0f, hy, 0f), mat, true)
            addDisc(radiusBottom, segments, transform * Mat4.translation(0f, -hy, 0f), mat, false)
        }
    }

    fun addDisc(radius: Float, segments: Int, transform: Mat4, mat: Material, up: Boolean) {
        val nrm = transform.transformDirection(if (up) Vec3(0f, 1f, 0f) else Vec3(0f, -1f, 0f)).normalized()
        val base = vertexCount
        vertex(transform.transformPoint(Vec3(0f, 0f, 0f)), nrm, mat)
        for (i in 0..segments) {
            val a = i.toFloat() / segments * 2f * Math.PI.toFloat()
            vertex(transform.transformPoint(Vec3(cos(a) * radius, 0f, sin(a) * radius)), nrm, mat)
        }
        for (i in 0 until segments) {
            if (up) triangle(base, base + i + 1, base + i + 2)
            else triangle(base, base + i + 2, base + i + 1)
        }
    }

    fun addSphere(radius: Float, segments: Int, transform: Mat4, mat: Material) {
        val rings = segments / 2
        val base = vertexCount
        for (i in 0..rings) {
            val phi = i.toFloat() / rings * Math.PI.toFloat()
            for (j in 0..segments) {
                val theta = j.toFloat() / segments * 2f * Math.PI.toFloat()
                val dir = Vec3(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta))
                vertex(
                    transform.transformPoint(dir * radius),
                    transform.transformDirection(dir).normalized(),
                    mat
                )
            }
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            val a = base + i * (segments + 1) + j
            val b = a + segments + 1
            quad(a, a + 1, b + 1, b)
        }
    }

    /** A tube swept along a path — used for the halo. */
    fun addTube(path: List<Vec3>, radius: Float, segments: Int, mat: Material, closed: Boolean = false) {
        if (path.size < 2) return
        val base = vertexCount
        val up = Vec3(0f, 1f, 0f)
        for (i in path.indices) {
            val prev = path[if (i == 0) (if (closed) path.size - 1 else 0) else i - 1]
            val next = path[if (i == path.size - 1) (if (closed) 0 else i) else i + 1]
            val tangent = (next - prev).normalized()
            var side = tangent.cross(up)
            if (side.length() < 1e-4f) side = tangent.cross(Vec3(1f, 0f, 0f))
            side = side.normalized()
            val upv = side.cross(tangent).normalized()
            for (j in 0..segments) {
                val a = j.toFloat() / segments * 2f * Math.PI.toFloat()
                val dir = (side * cos(a) + upv * sin(a)).normalized()
                vertex(path[i] + dir * radius, dir, mat)
            }
        }
        val ringSize = segments + 1
        val ringCount = if (closed) path.size else path.size - 1
        for (i in 0 until ringCount) {
            val next = (i + 1) % path.size
            for (j in 0 until segments) {
                val a = base + i * ringSize + j
                val b = base + next * ringSize + j
                quad(a, a + 1, b + 1, b)
            }
        }
    }

    /**
     * Loft a surface through a series of closed cross-sections. This is how the
     * monocoque gets its continuous shape, narrow at the nose and wide at the
     * cockpit, instead of being a stack of boxes.
     */
    fun addLoft(sections: List<List<Vec3>>, mat: Material, capEnds: Boolean = true) {
        if (sections.size < 2) return
        val ring = sections[0].size
        val base = vertexCount

        for (s in sections.indices) {
            val prev = sections[maxOf(0, s - 1)]
            val next = sections[minOf(sections.size - 1, s + 1)]
            for (i in 0 until ring) {
                val p = sections[s][i]
                // Normal from the cross-section's own tangents.
                val along = (next[i] - prev[i]).normalized()
                val around = (sections[s][(i + 1) % ring] - sections[s][(i - 1 + ring) % ring]).normalized()
                vertex(p, around.cross(along).normalized(), mat)
            }
        }
        for (s in 0 until sections.size - 1) {
            for (i in 0 until ring) {
                val a = base + s * ring + i
                val b = base + s * ring + (i + 1) % ring
                quad(a, a + ring, b + ring, b)
            }
        }
        if (capEnds) {
            capRing(sections.first(), mat, false)
            capRing(sections.last(), mat, true)
        }
    }

    private fun capRing(ring: List<Vec3>, mat: Material, forward: Boolean) {
        var centre = Vec3(0f, 0f, 0f)
        ring.forEach { centre = centre + it }
        centre = centre * (1f / ring.size)
        val n = (ring[1] - ring[0]).cross(ring[2] - ring[0]).normalized()
        val nrm = if (forward) n else n * -1f
        val base = vertexCount
        vertex(centre, nrm, mat)
        ring.forEach { vertex(it, nrm, mat) }
        for (i in ring.indices) {
            val a = base + 1 + i
            val b = base + 1 + (i + 1) % ring.size
            if (forward) triangle(base, a, b) else triangle(base, b, a)
        }
    }

    fun build(): Mesh = Mesh(verts.toFloatArray(), idx.toIntArray())
}
