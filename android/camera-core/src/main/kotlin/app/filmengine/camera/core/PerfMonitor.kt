package app.filmengine.camera.core

/**
 * Quality levels for the live preview degradation ladder (ARCHITECTURE.md §11).
 *
 * When the frame budget is exceeded the pipeline drops quality in discrete
 * steps — spatial-pass fidelity first, then spatial passes entirely — so the
 * core color transform (film sim) always renders.
 */
enum class QualityLevel {
    /** Full chain: film_sim + halation + grain at authored amounts. */
    FULL,
    /** Grain amount halved. */
    REDUCED_GRAIN,
    /** Grain dropped entirely; halation still present. */
    NO_GRAIN,
    /** All spatial nodes dropped; film_sim color-only. */
    HALF_RES_SPATIAL,
    /** Lightest: exposure + film_sim + sRGB output, nothing else. */
    MINIMAL,
}

/**
 * Tracks per-frame GPU time over a rolling window and outputs the current
 * [QualityLevel]. The degradation ladder is biased toward stability: degrade
 * quickly (30 bad frames), recover slowly (60 good frames), with hysteresis
 * to prevent oscillation between adjacent levels.
 *
 * Thread-safety: designed to be called from a single GL thread — no
 * synchronization.  The [currentLevel] and [averageMs] properties are read
 * from the UI thread via StateFlow in [PreviewPipeline], which publishes
 * snapshots.
 */
class PerfMonitor(
    /** Target frame time in milliseconds (16.6 ms for 60 FPS). */
    private val targetMs: Float = TARGET_60FPS_MS,
    /** Frames of sustained overrun before degrading one step. */
    private val degradeAfter: Int = DEGRADE_WINDOW,
    /** Frames of sustained headroom before recovering one step. */
    private val recoverAfter: Int = RECOVER_WINDOW,
    /** Headroom margin: recover only when p95 is below this. */
    private val recoverMs: Float = RECOVER_THRESHOLD_MS,
) {
    private val frameTimes = FloatArray(WINDOW_SIZE)
    private var index = 0
    private var count = 0

    private var overrunStreak = 0
    private var underrunStreak = 0

    var currentLevel: QualityLevel = QualityLevel.FULL
        private set

    /** Rolling average frame time in milliseconds. */
    var averageMs: Float = 0f
        private set

    /**
     * Record the wall-clock time of the most recent frame.
     * Returns `true` if the quality level changed (caller should recompile the plan).
     */
    fun recordFrame(frameTimeMs: Float): Boolean {
        frameTimes[index] = frameTimeMs
        index = (index + 1) % WINDOW_SIZE
        if (count < WINDOW_SIZE) count++

        averageMs = frameTimes.take(count).average().toFloat()

        val previousLevel = currentLevel

        if (frameTimeMs > targetMs) {
            overrunStreak++
            underrunStreak = 0
            if (overrunStreak >= degradeAfter) {
                degrade()
                overrunStreak = 0
            }
        } else if (frameTimeMs < recoverMs) {
            underrunStreak++
            overrunStreak = 0
            if (underrunStreak >= recoverAfter) {
                recover()
                underrunStreak = 0
            }
        } else {
            // In the band between recoverMs and targetMs — stable, reset both.
            overrunStreak = 0
            underrunStreak = 0
        }

        return currentLevel != previousLevel
    }

    /** Reset to FULL quality and clear history. */
    fun reset() {
        currentLevel = QualityLevel.FULL
        count = 0
        index = 0
        overrunStreak = 0
        underrunStreak = 0
        averageMs = 0f
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun percentile95(): Float {
        if (count == 0) return 0f
        val sorted = frameTimes.take(count).sorted()
        val idx = ((count - 1) * 0.95f).toInt().coerceIn(0, count - 1)
        return sorted[idx]
    }

    private fun degrade() {
        val values = QualityLevel.entries
        val next = values.indexOf(currentLevel) + 1
        if (next < values.size) currentLevel = values[next]
    }

    private fun recover() {
        val values = QualityLevel.entries
        val prev = values.indexOf(currentLevel) - 1
        if (prev >= 0) currentLevel = values[prev]
    }

    companion object {
        const val TARGET_60FPS_MS = 16.6f
        const val RECOVER_THRESHOLD_MS = 14.0f
        const val WINDOW_SIZE = 60
        const val DEGRADE_WINDOW = 30
        const val RECOVER_WINDOW = 60
    }
}
