# Project Checklist

Status tracker for the roadmap in [ARCHITECTURE.md](ARCHITECTURE.md) §22.
Legend: `[x]` done · `[~]` partial · `[ ]` not started.

## Done

### Foundations (M0) — commit `beb24f0`
- [x] Architecture specification (24 deliverables) — `docs/ARCHITECTURE.md`
- [x] Monorepo: Gradle 8.14 + Kotlin 2.1, JDK 21 toolchain, self-sufficient wrapper
- [x] `core/color-science`: Mat3 (multiply/inverse/in-place transform), sRGB + Rec.2020 ↔ XYZ derived from primaries against shared D65 (grey-invariance exact), sRGB transfer functions, Rec.2020 luma weights
- [x] `core/image-engine`: ImageBuffer (float RGBA) · node model (descriptors with param schemas, color-state contracts, string options) · GraphCompiler (edge ordering, dead-node elimination, disabled-node skip, cycle + color-state + param/option validation, defaults + clamping) · `RenderBackend` contract
- [x] `render/cpu-renderer`: reference backend — exposure, white balance, color matrix, tone curve (contrast placeholder), saturation, sRGB output transform
- [x] GitHub repo: https://github.com/kjn373/film-sim-engine

### Film simulation (M1, first half) — commit `bba4f59`
- [x] `core/film-engine`: parametric FilmStock — anchored-logistic H&D characteristic curve in log2 exposure (gamma/toe/shoulder/black/white, grey-anchored at 0.18), spectral sensitivity + dye crosstalk matrices (row-sum-1 enforced at construction), saturation
- [x] Three stocks: `chroma-100` (slide), `negato-400` (colour negative), `mono-400` (panchromatic B&W) — visually verified against test chart
- [x] `film_sim` node + CPU kernel (stock via option, push/strength params, fails loudly on missing/unknown stock)
- [x] `desktop/cli-renderer`: PNG/JPEG or synthetic chart in → film-simulated PNG out; bulk pixel I/O; validated args
- [x] Review-pass fixes: no buffer aliasing on empty plans, shared pointwise/Mat3 helpers, kernel purity contract documented

### GPU rendering (M1, second half) — commit `ba49daf`
- [x] `render/gpu-renderer`: desktop GL 3.3 core backend (LWJGL, hidden window) — one fragment pass per step, ping-pong RGBA32F FBOs, program cache
- [x] GLSL kernels for all seven node types (GLES 3.0-compatible subset, math mirrored 1:1 from CPU)
- [x] CPU↔GPU parity suite: every node, every stock, full chain within 2e-3 on an HDR card — **the contract between backends**
- [x] Test suite: 43 tests green (color science, compiler rules, curve semantics, neutrality contracts, hand-derived goldens, round-trips, parity)

## To Do

### Recipe engine (A1 + A2)
- [x] **A1. Recipe serialization**: ProcessGraph ↔ versioned JSON via kotlinx.serialization DTOs (wire format decoupled from engine types), schemaVersion + forward-only migration chain, pinned v1 fixture as field-name contract, same-major forward compat (unknown fields ignored), newer-schema rejection
- [x] **A2. `.filmrecipe` container**: ZIP with manifest.json (semver) + graph.json + preview + sha256 checksums.json; deterministic bytes (same recipe → same file); hardened reader — entry-count/per-entry/total size limits, path-traversal rejection, tamper detection, future-major rejection

### Spatial nodes (A3)
- [x] **A3. Spatial nodes** on both backends:
  - shared `Gaussian.weights()` in image-engine — both backends consume identical floats (this is what makes spatial parity hold)
  - `gaussian_blur` (separable H+V, clamp-to-edge), `bloom` (bright-pass → blur → additive composite), `halation` (red-orange tinted glow, film-engine node), `grain` (integer-hash noise, bit-identical Kotlin Int ↔ GLSL uint, midtone-peaked luminance response, seeded)
  - GPU: multi-pass `GlKernel` (pass list), three-target scheduling so a step's input survives for `orig` composite samplers, backend-provided `texelSize`
  - orientation settled: texel row 0 = image row 0 on upload/readback and `gl_FragCoord` — no flip anywhere in the engine (display flip is the platform layer's job)
  - parity extended: 4 spatial nodes + full creative chain (exposure → film → halation → bloom → grain → sRGB), all within 2e-3 on GPU
  - deferred: kernel-radius metadata on descriptors (needed by A7 tiling, added there)

### Filmic tone map (A5, first half)
- [x] **A5a. `tone_map` node**: ACES-fitted rational curve with exposure-bias param on both backends — monotonic, zero-anchored, bounded [0,1]; parity-tested. The neutral display transform for the non-film path (film stocks tone-map via their characteristic curve).

### Pass fusion (A4)
- [x] **A4. Pass fusion (D2)**: `PlanFusion` collapses each maximal run of ≥2 fusable pointwise steps into one baked-3D-LUT step (33³ default)
  - bake = render an N³ lattice through the actual CPU kernels — exact at lattice points by construction
  - domain shaper `log2(x/black + 1)` — exact at 0 (true black preserved through fusion), log-dense shadows, +6-stop ceiling
  - `fusable` flag on descriptors; `Step.inputState`; `BakedLut` payload; CPU trilinear kernel mirrors GLSL `sampler3D` (single-slot texture cache)
  - **finding:** the sRGB output transform must NOT be fused — its hard gamut clamp is a slope kink the OETF amplifies ~13× near black (measured 0.09 error on saturated cyan). It stays an exact analytic pass; a fused chain is LUT + output = 2 passes total
  - gates: fused-vs-unfused worst < 0.015 / mean < 0.003 on an HDR card; black exact; CPU↔GPU fused parity < 1.5e-2
  - follow-up (Phase A backlog): gamut-compression node to replace the hard clamp — fixes hue skew on saturated colors AND makes the output transform fusable
- [ ] **A5b. RAW develop nodes**: highlight reconstruction + shadow lift (meaningful once RAW/DNG input exists — Phase B)
- [x] **A6. Stock catalog growth**: 9 stocks total — chroma-64/100/200n (slide: punchy/classic/neutral), negato-160/400/800t (negative: portrait/warm/tungsten-night), mono-100/400/3200p (B&W: fine/classic/push) — all covered by the shared property suite (monotonic, grey-anchored, bounded, neutral matrices); new stocks visually verified. `tooling/film-lab` CurveFitter: grid + coordinate-descent fit of gamma/toe/shoulder from (stops, value) samples — recovers every builtin stock's curve to rmse < 2e-3; the pipeline for fitting real scan data later.
- [x] **A7. Tiled rendering**: `TiledRenderer` wraps any backend; compiler computes `ExecutionPlan.tileMargin` (Σ per-step `spatialRadius`); tiles expand by the margin so spatial nodes see full neighborhoods; grain receives absolute tile offsets (`tile_ox`/`tile_oy`) so its hash stays image-absolute. Gates: tiled CPU output is **bit-identical** to whole-image output (plain and fused plans); tiled GPU matches whole-image GPU < 1e-6.

**Phase A complete.** Parked follow-ups: A5b RAW develop nodes (needs Phase B RAW input), gamut-compression node (replaces srgb_output's hard clamp, unlocks fusing it).

### App scaffold (B1)
- [x] **B1. App scaffold**: `android/camera-app` builds `camera-app-debug.apk` (10.3 MB) — AGP 8.7.3 + Kotlin/Compose plugins coexisting with the JVM modules (root build applies kotlin-jvm only outside `:android`); Compose shell with permission gate; CameraX preview + JPEG capture to MediaStore (`Pictures/FilmEngine`); stock-selector strip fed by `film-engine`'s BuiltinStocks — engine modules verified linking into the APK through D8/R8. Android SDK (platform 35) installed via command-line tools. Hilt deferred to B2 with the session controller (`ponytail:` note in code).

### Phase B — Android camera app (remaining; on-device work needs a phone)
- [x] **B2. camera-core**: `android/camera-core` library — `CameraController` owns the CameraX session; manual controls via Camera2 interop (AE off + SENSOR_SENSITIVITY/EXPOSURE_TIME, AF off + LENS_FOCUS_DISTANCE, AE/AWB locks) composed and re-applied as one options set; live AE metering from session capture callbacks (ignored while AE is off); capability read (ISO/shutter ranges, min focus). Pure-Kotlin `ExposureSolver` (A/S/ISO-priority/M state machine, EC, clamp-with-offset-reporting) — 7 JVM unit tests. Hilt wired (2.56.2 + KSP): @HiltAndroidApp, @AndroidEntryPoint, @HiltViewModel CameraViewModel with frozen-AE-reference semantics; pro-controls UI panel (mode chips, log-scaled ISO/shutter sliders, EC, readout with clip warning). CI now runs `testDebugUnitTest`. On-device exercise pending a phone.
- [x] **B3. GLES backend**: `android/gles-renderer` library — `GlesContext` (EGL 1.4 pbuffer, GLES 3.1), `GlesRenderBackend` (same 3-target ping-pong as desktop GL, RGBA32F with RGBA16F fallback), `GlesKernels` (all shaders ported to `#version 300 es`, math character-identical), OES camera-texture input kernel (`samplerExternalOES` + `SurfaceTexture` transform matrix blit to RGBA intermediate); wired into `camera-core`. On-device exercise deferred to B4.
- [x] **B4. Live film preview**: `PreviewPipeline` (GL HandlerThread, EGL window surface, OES camera-texture input, per-frame render loop), `PreviewRenderer` in gles-renderer (zero-allocation steady-state: persistent 3-target ping-pong FBOs, program cache, blit-to-screen — no `glGen*`/`glReadPixels` in the hot path), `PreviewGraph` (builds ProcessGraph per stock + quality level, combined NodeRegistry via new `+` operator), `PerfMonitor` (rolling 60-frame window, 5-level degradation ladder: FULL → REDUCED_GRAIN → NO_GRAIN → HALF_RES_SPATIAL → MINIMAL, degrade after 30 overruns / recover after 60 fast frames with hysteresis), `GlesContext` promoted to public + `createWindowSurface`/`swapBuffers`/`destroySurface`; CameraScreen uses SurfaceView (processed preview) instead of PreviewView, stock chips wired to pipeline with "None" passthrough, debug HUD (frame-time + quality level). 17 new unit tests (PerfMonitor + PreviewGraph); 24 camera-core tests green. On-device exercise pending a phone.
- [x] **B5. Capture pipeline**: capture → MediaStore immediately → WorkManager full-quality render. `CameraController` gains RAW: capability probe via `ImageCapture.getImageCaptureCapabilities` (CameraX 1.5.3), `OUTPUT_FORMAT_RAW_JPEG` simultaneous DNG+JPEG capture (use case swapped in place on toggle, controls re-applied), `takePhoto` returns the saved JPEG Uri. `CaptureRender` (camera-core, pure JVM): FULL-quality plan = the preview graph's export profile; sRGB-ARGB ↔ linear-Rec.2020 ImageBuffer codec — 5 unit tests incl. full-chain render through the CPU backend. `RenderWorker` (CoroutineWorker): EXIF-aware software decode capped at 2560px, TiledRenderer + CPU backend, saves `<name>_<stock>` JPEG to MediaStore; enqueued after capture when a stock is selected. RAW chip in UI shown only when supported. Deferred: HEIF output + true full-res export (B8 tiled export), GPU export context (B8). On-device exercise pending a phone.
- [x] **B6. Scopes**: `ScopeRenderer` (gles-renderer) — GLES 3.1 compute shader, atomic-add luma+RGB histograms (256 bins) + 128×64 waveform into one SSBO, stride-2 sampling (¼ the pixels), sync readback (ponytail: fence + double-buffer if it shows in traces); self-disables on ES 3.0-only contexts. Zebra (clip stripes ≥0.98) + focus peaking (luma-Laplacian red overlay) as blit-time-only overlays — the returned final texture stays clean so scopes never bin overlay pixels. `ScopeGate` (pure, 4 tests): every 4th frame at FULL/REDUCED_GRAIN, every 8th at NO_GRAIN, off once spatial passes degrade — scope cost lands inside the PerfMonitor-measured frame so the ladder throttles it (§11 "drop grain quality → drop scope rate"). UI: Scope chip cycles Off→Hist→Wave, Zebra/Peak chips, Compose Canvas overlay (RGB+luma histogram bars, log-scaled waveform heat bitmap). On-device exercise pending a phone. Deferred: vectorscope + false color (§11 lists them; add with the editor's grading tools when there's a use), pre-film-sim peaking tap.
- [ ] **B7. Device profile v1** for the reference device (black levels, dual-illuminant matrices, noise ladder) via `tooling/profile-calibrator`
- [ ] **B8. Editor**: layer stack UI → graph compile, Room edit sessions + append-only version history, tiled export, HEIF

### Backend foundation (C1 + C2)
- [x] **C1. backend-api**: Ktor 3 modular monolith (service boundaries = packages per D6), HikariCP + Flyway (V1: users, auth_credentials, refresh_tokens, recipes, recipe_versions), problem-JSON error pages, JDBC off the event loop (`Db.tx` on Dispatchers.IO), Testcontainers suite against real Postgres 16
- [x] **C2. Auth**: Argon2id hashing (timing-equalized login), JWT access (15 min, HS256) + opaque rotating refresh tokens (sha256-stored, single-use via atomic `DELETE … RETURNING`), input validation at the boundary, full-flow integration tests (register → me → 409/401 paths → refresh rotation + reuse rejection)
- [x] Local Testcontainers note: Docker Engine 29 dropped API < 1.44 — pinned `api.version=1.44` on the test task; `~/.testcontainers.properties` points at Docker Desktop's `dockerDesktopLinuxEngine` pipe

### Phase C — backend & community (remaining)
- [ ] **C3. Recipes API**: CRUD, versions, forks (lineage), presigned uploads, container validation worker
- [ ] **C4. Community**: likes/ratings/comments/follows, feeds (Redis), cursor sync protocol
- [ ] **C5. OTA device profiles** endpoint + client refresh job

### Phase D — ecosystem
- [ ] **D1. Content plugins**: `.filmpack` install/registry (data-only tier)
- [ ] **D2. desktop-viewer**: Compose Desktop shell over the same engine; CLI renderer byte-parity check vs device
- [ ] **D3. Marketplace + entitlements**; pack signing (Ed25519)
- [ ] **D4. Vulkan backend spike** behind the existing `RenderBackend` seam
- [ ] **D5. KMP conversion** of `core/*` (mechanical — modules are already pure Kotlin); WASM preview spike
- [ ] **D6. AI node tier**: NR, semantic masks, film matching (kernel type `ai` per descriptor)

### Standing engineering tasks (each phase)
- [x] CI: GitHub Actions — build + test on push/PR (green on `main`); GPU parity tests self-skip headless with a reported count; gradlew executable bit fixed for Linux runners
- [ ] Konsist architecture rules (no Android imports in `core/*`, dependency direction)
- [ ] Benchmark harness (`tooling/benchmark`) once spatial nodes land
- [ ] Golden-image corpus with perceptual diff once exports exist
