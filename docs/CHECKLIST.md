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

### Phase A — engine depth (pure Kotlin/GL, verifiable on this machine)
- [ ] **A1. Recipe serialization**: ProcessGraph ↔ JSON via kotlinx.serialization, embedded schema version, round-trip property tests
- [ ] **A2. `.filmrecipe` container** (`core/recipe-engine`): ZIP + manifest.json + preview + checksums; forward-only migration chain with historical fixtures; malicious-container tests (zip bomb, path traversal)
- [ ] **A3. Spatial nodes** on both backends: separable Gaussian → bloom (threshold + blur + screen), halation (red-weighted, pre-tonemap in film subgraph), procedural grain — includes y-flip handling and kernel-radius metadata on descriptors
- [ ] **A4. Pass fusion (D2)**: compile-time fusion of pointwise runs into a baked 3D LUT pass; parity + banding tests; perf comparison logged
- [ ] **A5. Filmic tone map**: parametric spline replacing the contrast placeholder; highlight reconstruction + shadow lift nodes
- [ ] **A6. Stock catalog growth**: 6+ additional stocks; `tooling/film-lab` fitting skeleton (curve fit from datasheet/scan pairs)
- [ ] **A7. Tiled rendering**: 512px tiles + overlap = max kernel radius, for large exports on both backends

### Phase B — Android camera app (needs Android SDK + device)
- [ ] **B1. App scaffold**: `android/camera-app` — Android Gradle plugin, Hilt, Compose shell, module wiring per ARCHITECTURE §3
- [ ] **B2. camera-core**: CameraX session + Camera2 interop (ISO, shutter, MF, WB, AE/AF lock, exposure comp)
- [ ] **B3. GLES backend**: port gpu-renderer pass structure to GLES 3.1 + OES camera texture input (same GLSL sources)
- [ ] **B4. Live film preview**: preview-profile graph compilation, 60 FPS target, degradation ladder
- [ ] **B5. Capture pipeline**: JPEG/HEIF + DNG via Camera2 → MediaStore immediately → WorkManager full-quality render
- [ ] **B6. Scopes**: histogram/RGB histogram/waveform via compute shader on ¼-res, zebra, focus peaking
- [ ] **B7. Device profile v1** for the reference device (black levels, dual-illuminant matrices, noise ladder) via `tooling/profile-calibrator`
- [ ] **B8. Editor**: layer stack UI → graph compile, Room edit sessions + append-only version history, tiled export, HEIF

### Phase C — backend & community
- [ ] **C1. backend-api**: Ktor modular monolith, Postgres schema (ARCHITECTURE §13), Flyway migrations, Testcontainers suite
- [ ] **C2. Auth**: JWT access + rotating refresh, Argon2id
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
- [ ] CI: GitHub Actions — build + test on PR; parity suite headless story (GPU runner or skip-with-report)
- [ ] Konsist architecture rules (no Android imports in `core/*`, dependency direction)
- [ ] Benchmark harness (`tooling/benchmark`) once spatial nodes land
- [ ] Golden-image corpus with perceptual diff once exports exist
