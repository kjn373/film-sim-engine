package app.filmengine.camera.core

/**
 * Decides when the scope compute pass runs (B6). Pure logic, GL-free.
 *
 * Scopes never run every frame — the readback stalls the GL pipeline — and
 * they follow the perf ladder before spatial quality does (ARCHITECTURE.md
 * §11: "drop grain quality → drop scope rate"): every 4th frame at full
 * quality, every 8th at NO_GRAIN, off entirely once spatial passes degrade.
 */
object ScopeGate {

    /** Frames between collections at the given quality level; 0 = scopes off. */
    fun interval(quality: QualityLevel): Int = when {
        quality <= QualityLevel.REDUCED_GRAIN -> 4
        quality == QualityLevel.NO_GRAIN -> 8
        else -> 0
    }

    fun shouldCollect(frameIndex: Long, enabled: Boolean, quality: QualityLevel): Boolean {
        if (!enabled) return false
        val n = interval(quality)
        return n > 0 && frameIndex % n == 0L
    }
}
