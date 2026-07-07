package app.filmengine.color

import kotlin.math.abs

/** Row-major 3x3 matrix over Float. */
class Mat3(val m: FloatArray) {
    init {
        require(m.size == 9) { "Mat3 requires 9 elements, got ${m.size}" }
    }

    operator fun times(o: Mat3): Mat3 {
        val a = m
        val b = o.m
        val r = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] =
                    a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
            }
        }
        return Mat3(r)
    }

    /** Transforms an RGB/XYZ triple. */
    fun transform(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[3] * x + m[4] * y + m[5] * z,
        m[6] * x + m[7] * y + m[8] * z,
    )

    /** In-place transform of the first three elements of [v] — allocation-free for pixel loops. */
    fun transform(v: FloatArray) {
        val x = v[0]; val y = v[1]; val z = v[2]
        v[0] = m[0] * x + m[1] * y + m[2] * z
        v[1] = m[3] * x + m[4] * y + m[5] * z
        v[2] = m[6] * x + m[7] * y + m[8] * z
    }

    fun inverse(): Mat3 {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val ca = e * i - f * h
        val cb = f * g - d * i
        val cc = d * h - e * g
        val det = a * ca + b * cb + c * cc
        require(abs(det) > 1e-12f) { "Matrix is singular" }
        val s = 1f / det
        return Mat3(
            floatArrayOf(
                ca * s, (c * h - b * i) * s, (b * f - c * e) * s,
                cb * s, (a * i - c * g) * s, (c * d - a * f) * s,
                cc * s, (b * g - a * h) * s, (a * e - b * d) * s,
            )
        )
    }

    companion object {
        val IDENTITY = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    }
}
