package app.filmengine.render.gl

import app.filmengine.color.ColorSpaces
import app.filmengine.engine.exec.Step
import app.filmengine.engine.node.Gaussian
import app.filmengine.film.BuiltinStocks
import org.lwjgl.opengl.GL33.glGetUniformLocation
import org.lwjgl.opengl.GL33.glUniform1f
import org.lwjgl.opengl.GL33.glUniform1fv
import org.lwjgl.opengl.GL33.glUniform1i
import org.lwjgl.opengl.GL33.glUniformMatrix3fv
import kotlin.math.pow

/**
 * One fragment shader per node type, math mirrored 1:1 from the CPU kernels —
 * the parity suite (GpuCpuParityTest) is the contract between the two.
 * GLSL kept to the GLES 3.0-compatible subset; only the #version line differs
 * on Android.
 */
// ponytail: no pass fusion yet — every node is its own fullscreen pass (or passes).
// The 3D-LUT fusion pass (ARCHITECTURE.md D2) replaces this when preview perf matters.
class GlPass(val fragmentSrc: String, val bind: (program: Int, step: Step) -> Unit)

/**
 * A node's GPU implementation: one or more sequential fullscreen passes.
 * Backend-provided uniforms every pass may declare: `src` (previous pass output),
 * `orig` (the step's input, for composites), `texelSize` (1/w, 1/h).
 */
class GlKernel(val passes: List<GlPass>) {
    constructor(fragmentSrc: String, bind: (program: Int, step: Step) -> Unit) :
        this(listOf(GlPass(fragmentSrc, bind)))
}

object GlKernels {

    val VERTEX = """
        #version 330 core
        out vec2 uv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            uv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private fun frag(body: String) = """
        #version 330 core
        in vec2 uv;
        out vec4 fragColor;
        uniform sampler2D src;
    """.trimIndent() + "\n" + body.trimIndent()

    private fun loc(p: Int, name: String) = glGetUniformLocation(p, name)

    // row-major FloatArray -> GLSL mat3 (transpose=true)
    private fun setMat3(p: Int, name: String, rowMajor: FloatArray) =
        glUniformMatrix3fv(loc(p, name), true, rowMajor)

    private val exposure = GlKernel(
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

    private val whiteBalance = GlKernel(
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

    private val colorMatrix = GlKernel(
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

    private val toneCurve = GlKernel(
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

    private val saturation = GlKernel(
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

    private val filmSim = GlKernel(
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

    private val srgbOutput = GlKernel(
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
        glUniform1fv(loc(p, "weights"), w)
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

    private val gaussianBlur = GlKernel(
        listOf(GlPass(blurFrag(true), bindBlur), GlPass(blurFrag(false), bindBlur))
    )

    private val bloom = GlKernel(
        listOf(
            GlPass(brightPassFrag, bindThreshold),
            GlPass(blurFrag(true), bindBlur),
            GlPass(blurFrag(false), bindBlur),
            GlPass(
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

    private val halation = GlKernel(
        listOf(
            GlPass(brightPassFrag, bindThreshold),
            GlPass(blurFrag(true), bindBlur),
            GlPass(blurFrag(false), bindBlur),
            GlPass(
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

    private val grain = GlKernel(
        frag(
            """
            uniform float amount;
            uniform float seed;
            void main() {
                vec4 c = texture(src, uv);
                uvec2 pix = uvec2(ivec2(gl_FragCoord.xy));
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
    }

    val all: Map<String, GlKernel> = mapOf(
        "exposure" to exposure,
        "white_balance" to whiteBalance,
        "color_matrix" to colorMatrix,
        "tone_curve" to toneCurve,
        "saturation" to saturation,
        "film_sim" to filmSim,
        "srgb_output" to srgbOutput,
        "gaussian_blur" to gaussianBlur,
        "bloom" to bloom,
        "halation" to halation,
        "grain" to grain,
    )
}
