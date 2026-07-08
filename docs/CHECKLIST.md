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

### Phase B — Android camera app (needs Android SDK + device)
- [ ] **B1. App scaffold**: `android/camera-app` — Android Gradle plugin, Hilt, Compose shell, module wiring per ARCHITECTURE §3
- [ ] **B2. camera-core**: CameraX session + Camera2 interop (ISO, shutter, MF, WB, AE/AF lock, exposure comp)
- [ ] **B3. GLES backend**: port gpu-renderer pass structure to GLES 3.1 + OES camera texture input (same GLSL sources)
- [ ] **B4. Live film preview**: preview-profile graph compilation, 60 FPS target, degradation ladder
- [ ] **B5. Capture pipeline**: JPEG/HEIF + DNG via Camera2 → MediaStore immediately → WorkManager full-quality render
- [ ] **B6. Scopes**: histogram/RGB histogram/waveform via compute shader on ¼-res, zebra, focus peaking
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
