package app.filmengine.render.gles

import android.opengl.GLES31.*
import app.filmengine.color.ColorSpaces
import app.filmengine.engine.exec.BakedLut
import app.filmengine.engine.exec.Step
import app.filmengine.engine.node.Gaussian
import app.filmengine.film.BuiltinStocks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.pow

/**
 * One fragment shader per node type, math mirrored 1:1 from the CPU kernels.
 * GLSL uses `#version 300 es` (GLES 3.0 compatible subset) — identical to the
 * desktop GL `#version 330 core` shaders modulo the version line and precision.
 *
 * The parity guarantee is transitive: desktop GPU ↔ CPU parity is tested in
 * gpu-renderer; the GLSL math here is character-identical, so GLES ↔ CPU parity
 * holds by construction. On-device spot checks (B4+) will confirm this.
 */
class GlesPass(val fragmentSrc: String, val bind: (program: Int, step: Step) -> Unit)

/**
 * A node's GLES implementation: one or more sequential fullscreen passes.
 * Backend-provided uniforms every pass may declare: `src` (previous pass output),
 * `orig` (the step's input, for composites), `texelSize` (1/w, 1/h).
 */
class GlesKernel(val passes: List<GlesPass>) {
    constructor(fragmentSrc: String, bind: (program: Int, step: Step) -> Unit) :
        this(listOf(GlesPass(fragmentSrc, bind)))
}

object GlesKernels {

    val VERTEX = """
        #version 300 es
        out vec2 uv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            uv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private fun frag(body: String) = """
        #version 300 es
        precision highp float;
        in vec2 uv;
        out vec4 fragColor;
        uniform sampler2D src;
    """.trimIndent() + "\n" + body.trimIndent()

    private fun loc(p: Int, name: String) = glGetUniformLocation(p, name)

    // row-major FloatArray -> GLSL mat3 (transpose=true)
    private fun setMat3(p: Int, name: String, rowMajor: FloatArray) =
        glUniformMatrix3fv(loc(p, name), 1, true, rowMajor, 0)

    private val exposure = GlesKernel(
        frag(
            """
            uniform float gain;
            void main() {
                vec4 c = texture(src, uv);
                fragColor = vec4(c.rgb * gain, c.a);
            }
            """
        )
    ) { p, s -> glUniform1f(loc(p, "gain"), 2f.pow(s.params.getValue("stops"))) }

    private val whiteBalance = GlesKernel(
        frag(
            """
            uniform float rGain;
            uniform float bGain;
            void main() {
                vec4 c = texture(src, uv);
                fragColor = vec4(c.r * rGain, c.g, c.b * bGain, c.a);
            }
            """
        )
    ) { p, s ->
        glUniform1f(loc(p, "rGain"), s.params.getValue("r_gain"))
        glUniform1f(loc(p, "bGain"), s.params.getValue("b_gain"))
    }

    private val colorMatrix = GlesKernel(
        frag(
            """
            uniform mat3 mtx;
            void main() {
                vec4 c = texture(src, uv);
                fragColor = vec4(mtx * c.rgb, c.a);
            }
            """
        )
    ) { p, s -> setMat3(p, "mtx", FloatArray(9) { s.params.getValue("m${it / 3}${it % 3}") }) }

    private val toneCurve = GlesKernel(
        frag(
            """
            uniform float contrast;
            void main() {
                vec4 c = texture(src, uv);
                vec3 x = max(c.rgb, vec3(0.0));
                fragColor = vec4(0.18 * pow(x / 0.18, vec3(contrast)), c.a);
            }
            """
        )
    ) { p, s -> glUniform1f(loc(p, "contrast"), s.params.getValue("contrast")) }

    private val toneMap = GlesKernel(
        frag(
            """
            uniform float gain;
            void main() {
                vec4 c = texture(src, uv);
                vec3 x = max(c.rgb, vec3(0.0)) * gain;
                vec3 o = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
                fragColor = vec4(clamp(o, 0.0, 1.0), c.a);
            }
            """
        )
    ) { p, s -> glUniform1f(loc(p, "gain"), 2f.pow(s.params.getValue("exposure_bias"))) }

    private val saturation = GlesKernel(
        frag(
            """
            uniform float amount;
            void main() {
                vec4 c = texture(src, uv);
                float luma = dot(c.rgb, vec3(0.2627, 0.6780, 0.0593));
                fragColor = vec4(vec3(luma) + (c.rgb - vec3(luma)) * amount, c.a);
            }
            """
        )
    ) { p, s -> glUniform1f(loc(p, "amount"), s.params.getValue("amount")) }

    private val filmSim = GlesKernel(
        frag(
            """
            uniform mat3 sensitivity;
            uniform mat3 dye;
            uniform float curveGamma;
            uniform float curveToe;
            uniform float curveShoulder;
            uniform float curveBlack;
            uniform float curveWhite;
            uniform float satAmount;
            uniform float push;
            uniform float strength;

            const float MID = 0.18;

            float curveEval(float x) {
                float e = log2(max(x, 1e-6) / MID) + push;
                float t = e * curveGamma;
                float tt = t >= 0.0 ? t / curveShoulder : t / curveToe;
                float s0 = (MID - curveBlack) / (curveWhite - curveBlack);
                float k = 1.0 / s0 - 1.0;
                float s = 1.0 / (1.0 + k * exp2(-tt));
                return curveBlack + (curveWhite - curveBlack) * s;
            }

            void main() {
                vec4 c = texture(src, uv);
                vec3 o = sensitivity * c.rgb;
                o = vec3(curveEval(o.r), curveEval(o.g), curveEval(o.b));
                o = dye * o;
                float luma = dot(o, vec3(0.2627, 0.6780, 0.0593));
                o = vec3(luma) + (o - vec3(luma)) * satAmount;
                o = c.rgb + (o - c.rgb) * strength;
                fragColor = vec4(o, c.a);
            }
            """
        )
    ) { p, s ->
        val stock = BuiltinStocks.byId(
            s.options["stock"] ?: error("film_sim node '${s.nodeId}' requires a 'stock' option")
        )
        setMat3(p, "sensitivity", stock.sensitivity.m)
        setMat3(p, "dye", stock.dye.m)
        glUniform1f(loc(p, "curveGamma"), stock.curve.gamma)
        glUniform1f(loc(p, "curveToe"), stock.curve.toe)
        glUniform1f(loc(p, "curveShoulder"), stock.curve.shoulder)
        glUniform1f(loc(p, "curveBlack"), stock.curve.black)
        glUniform1f(loc(p, "curveWhite"), stock.curve.white)
        glUniform1f(loc(p, "satAmount"), stock.saturation)
        glUniform1f(loc(p, "push"), s.params.getValue("push"))
        glUniform1f(loc(p, "strength"), s.params.getValue("strength"))
    }

    private val srgbOutput = GlesKernel(
        frag(
            """
            uniform mat3 toSrgb;
            vec3 oetf(vec3 v) {
                vec3 lo = v * 12.92;
                vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), v));
            }
            void main() {
                vec4 c = texture(src, uv);
                vec3 l = clamp(toSrgb * c.rgb, 0.0, 1.0);
                fragColor = vec4(oetf(l), c.a);
            }
            """
        )
    ) { p, _ -> setMat3(p, "toSrgb", ColorSpaces.REC2020_TO_SRGB.m) }

    // ── spatial passes ──────────────────────────────────────────────────────

    private fun blurFrag(horizontal: Boolean) = frag(
        """
        uniform vec2 texelSize;
        uniform float weights[${Gaussian.MAX_RADIUS + 1}];
        uniform int radius;
        void main() {
            vec4 acc = vec4(0.0);
            for (int i = -radius; i <= radius; i++) {
                vec2 off = ${if (horizontal) "vec2(texelSize.x * float(i), 0.0)" else "vec2(0.0, texelSize.y * float(i))"};
                acc += weights[abs(i)] * texture(src, uv + off);
            }
            fragColor = acc;
        }
        """
    )

    private val bindBlur: (Int, Step) -> Unit = { p, s ->
        val w = Gaussian.weights(s.params.getValue("sigma"))
        glUniform1fv(loc(p, "weights"), w.size, w, 0)
        glUniform1i(loc(p, "radius"), w.size - 1)
    }

    private val brightPassFrag = frag(
        """
        uniform float threshold;
        void main() {
            vec4 c = texture(src, uv);
            fragColor = vec4(max(c.rgb - vec3(threshold), vec3(0.0)), c.a);
        }
        """
    )

    private val bindThreshold: (Int, Step) -> Unit = { p, s ->
        glUniform1f(loc(p, "threshold"), s.params.getValue("threshold"))
    }

    private val gaussianBlur = GlesKernel(
        listOf(GlesPass(blurFrag(true), bindBlur), GlesPass(blurFrag(false), bindBlur))
    )

    private val bloom = GlesKernel(
        listOf(
            GlesPass(brightPassFrag, bindThreshold),
            GlesPass(blurFrag(true), bindBlur),
            GlesPass(blurFrag(false), bindBlur),
            GlesPass(
                frag(
                    """
                    uniform sampler2D orig;
                    uniform float intensity;
                    void main() {
                        vec4 o = texture(orig, uv);
                        fragColor = vec4(o.rgb + texture(src, uv).rgb * intensity, o.a);
                    }
                    """
                )
            ) { p, s -> glUniform1f(loc(p, "intensity"), s.params.getValue("intensity")) },
        )
    )

    private val halation = GlesKernel(
        listOf(
            GlesPass(brightPassFrag, bindThreshold),
            GlesPass(blurFrag(true), bindBlur),
            GlesPass(blurFrag(false), bindBlur),
            GlesPass(
                frag(
                    """
                    uniform sampler2D orig;
                    uniform float strength;
                    void main() {
                        vec4 o = texture(orig, uv);
                        fragColor = vec4(o.rgb + texture(src, uv).rgb * vec3(1.0, 0.35, 0.10) * strength, o.a);
                    }
                    """
                )
            ) { p, s -> glUniform1f(loc(p, "strength"), s.params.getValue("strength")) },
        )
    )

    private val grain = GlesKernel(
        frag(
            """
            uniform float amount;
            uniform float seed;
            uniform vec2 tileOffset;
            void main() {
                vec4 c = texture(src, uv);
                uvec2 pix = uvec2(ivec2(gl_FragCoord.xy) + ivec2(tileOffset));
                uint h = pix.x * 1664525u + pix.y * 1013904223u + uint(seed);
                h ^= h >> 16u; h *= 0x45d9f3bu;
                h ^= h >> 16u; h *= 0x45d9f3bu;
                h ^= h >> 16u;
                float n = float(h & 0xFFFFFFu) / 16777216.0;
                float luma = clamp(dot(c.rgb, vec3(0.2627, 0.6780, 0.0593)), 0.0, 1.0);
                float response = 2.0 * sqrt(luma * (1.0 - luma));
                fragColor = vec4(c.rgb * (1.0 + amount * (n - 0.5) * response), c.a);
            }
            """
        )
    ) { p, s ->
        glUniform1f(loc(p, "amount"), s.params.getValue("amount"))
        glUniform1f(loc(p, "seed"), s.params.getValue("seed"))
        glUniform2f(
            loc(p, "tileOffset"),
            s.params[app.filmengine.engine.exec.TiledRenderer.TILE_OX] ?: 0f,
            s.params[app.filmengine.engine.exec.TiledRenderer.TILE_OY] ?: 0f,
        )
    }

    // ── baked LUT (pass fusion, D2) ─────────────────────────────────────────

    /** Single-slot 3D-texture cache: re-uploads only when the LUT instance changes. */
    private class LutSlot {
        var tex = 0
        var lut: BakedLut? = null
    }

    private val lutSlot = LutSlot()

    private val bakedLut = GlesKernel(
        frag(
            """
            uniform highp sampler3D lut;
            uniform float lutSize;
            uniform float shaperBlack;
            uniform float shaperWhite;
            void main() {
                vec4 c = texture(src, uv);
                float k = log2(shaperWhite / shaperBlack + 1.0);
                vec3 s = log2(clamp(c.rgb, 0.0, shaperWhite) / shaperBlack + 1.0) / k;
                vec3 coord = (s * (lutSize - 1.0) + 0.5) / lutSize;
                fragColor = vec4(texture(lut, coord).rgb, c.a);
            }
            """
        )
    ) { p, s ->
        val lut = s.lut ?: error("baked_lut step '${s.nodeId}' has no LUT payload")
        if (lutSlot.lut !== lut) {
            if (lutSlot.tex != 0) glDeleteTextures(1, intArrayOf(lutSlot.tex), 0)
            val texIds = IntArray(1)
            glGenTextures(1, texIds, 0)
            lutSlot.tex = texIds[0]
            glBindTexture(GL_TEXTURE_3D, lutSlot.tex)
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
            val buf = floatBuffer(lut.data)
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA32F, lut.size, lut.size, lut.size, 0, GL_RGBA, GL_FLOAT, buf)
            lutSlot.lut = lut
        }
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_3D, lutSlot.tex)
        glUniform1i(loc(p, "lut"), 2)
        glActiveTexture(GL_TEXTURE0)
        glUniform1f(loc(p, "lutSize"), s.params.getValue(BakedLut.KEY_SIZE))
        glUniform1f(loc(p, "shaperBlack"), s.params.getValue(BakedLut.KEY_SHAPER_BLACK))
        glUniform1f(loc(p, "shaperWhite"), s.params.getValue(BakedLut.KEY_SHAPER_WHITE))
    }

    // ── OES camera texture input ────────────────────────────────────────────

    /**
     * Samples an external OES texture (camera hardware surface) and writes it
     * into an RGBA32F intermediate. This decouples the external-texture lifetime
     * from the ping-pong loop and lets the rest of the pipeline work on normal
     * 2D textures.
     *
     * The `texTransform` uniform is the 4×4 matrix from
     * `SurfaceTexture.getTransformMatrix()` — it maps UV coords to the
     * texture's actual data rectangle (handles orientation, crop, etc.).
     *
     * Requires `GL_OES_EGL_image_external_essl3` on the device (universal on
     * GLES 3.0+ Android devices since ~2014).
     */
    val OES_INPUT_FRAGMENT = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        in vec2 uv;
        out vec4 fragColor;
        uniform samplerExternalOES camTex;
        uniform mat4 texTransform;
        void main() {
            vec2 t = (texTransform * vec4(uv, 0.0, 1.0)).xy;
            fragColor = texture(camTex, t);
        }
    """.trimIndent()

    /** Bind callback for the OES input kernel — receives the transform matrix via [GlesRenderBackend]. */
    internal val oesInputBind: (program: Int, oesTextureId: Int, transform: FloatArray) -> Unit =
        { program, oesTexId, transform ->
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexId)
            glUniform1i(loc(program, "camTex"), 0)
            glUniformMatrix4fv(loc(program, "texTransform"), 1, false, transform, 0)
        }

    val all: Map<String, GlesKernel> = mapOf(
        "exposure" to exposure,
        "white_balance" to whiteBalance,
        "color_matrix" to colorMatrix,
        "tone_curve" to toneCurve,
        "tone_map" to toneMap,
        "saturation" to saturation,
        "film_sim" to filmSim,
        "srgb_output" to srgbOutput,
        "gaussian_blur" to gaussianBlur,
        "bloom" to bloom,
        "halation" to halation,
        "grain" to grain,
        BakedLut.TYPE to bakedLut,
    )

    // ── helpers ──────────────────────────────────────────────────────────────

    /** GLES glTexImage3D needs a direct ByteBuffer, not a Kotlin FloatArray. */
    internal fun floatBuffer(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data).flip()
        return buf
    }

    /**
     * `GL_TEXTURE_EXTERNAL_OES` constant — the value is defined by the OES
     * extension and is the same on every Android device.
     */
    const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
}
