# FilmEngine Platform — Architecture Specification

**Version:** 1.0-draft · **Status:** For team review
**Scope:** Commercial computational photography platform — professional film-simulation camera, non-destructive editor, recipe community, cross-platform processing engine.

---

## 0. Reading Guide

Every major decision is written as:

> **Decision Dn:** what we chose. **Alternatives:** what we rejected. **Why:** the reasoning.

The document is ordered so each section builds on the previous: engine first (it is the product), then camera, then app, then backend.

---

## 1. Overall Architecture

### 1.1 The one sentence

A **platform-agnostic image processing engine** (node-graph, GPU-first, scene-referred color) consumed by three shells — an Android camera/editor app, a desktop viewer, and a server-side renderer — with a backend that stores and distributes **recipes** (portable, versioned descriptions of processing graphs).

### 1.2 System context

```
┌────────────────────────────────────────────────────────────────────┐
│                            MONOREPO                                │
│                                                                    │
│  ┌──────────────────── platform-agnostic core ─────────────────┐   │
│  │  image-engine   film-engine   recipe-engine   color-science  │   │
│  │  device-profiles              plugin-sdk                     │   │
│  └───────────────────────────────┬──────────────────────────────┘   │
│                                  │ (pure Kotlin, KMP-ready)         │
│  ┌───────────────┐   ┌───────────┴───────────┐   ┌───────────────┐  │
│  │ gpu-renderer  │   │      camera-core      │   │ cpu-renderer  │  │
│  │ GLES/Vulkan   │   │ CameraX + Camera2     │   │ reference impl│  │
│  └───────┬───────┘   └───────────┬───────────┘   └───────┬───────┘  │
│          │                       │                       │          │
│  ┌───────┴───────────────────────┴───────┐   ┌───────────┴───────┐  │
│  │            camera-app (Android)       │   │  desktop-viewer   │  │
│  │  Compose · Hilt · Room · WorkManager  │   │  cli-renderer     │  │
│  └───────────────────┬───────────────────┘   └───────────────────┘  │
│                      │ REST v1                                      │
│  ┌───────────────────┴───────────────────────────────────────────┐  │
│  │ backend-api  (Ktor modular monolith → services)                │  │
│  │ auth · recipes · community · sync · marketplace · processing   │  │
│  │ PostgreSQL · Redis · S3-compatible object storage              │  │
│  └────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

### 1.3 Architectural style

- **Clean Architecture** per module: `domain` (pure Kotlin, no framework), `data` (repositories, Room/network), `presentation` (Compose + MVVM ViewModels).
- **Dependency rule:** everything points inward toward `image-engine` domain types. The engine imports *nothing* from Android, Compose, or the backend.
- **Event-driven:** camera frames, edit operations, and sync events flow through Kotlin `Flow`; the engine is a pure function of `(image, graph, profile) → image`, which makes it trivially testable and cacheable.
- **Offline-first:** Room is the source of truth on device; the backend is a sync target. Every community feature degrades gracefully to local-only.

> **Decision D1 — Engine core in Kotlin (KMP-ready), heavy math in GPU shaders, C++ rejected for v1.**
> **Alternatives:** (a) C++ core with JNI (Lightroom/Darktable model), (b) Halide, (c) pure Kotlin CPU.
> **Why:** The performance-critical math lives in *shaders*, not host code — the host language mostly builds graphs and dispatches passes, so C++ buys little and costs JNI friction, a second toolchain, and slower iteration. Kotlin common code compiles to Android, Desktop (JVM), Native, and WASM via KMP, satisfying the cross-platform requirement with one language. GLSL itself is portable to desktop GL and (via naga/SPIRV-Cross) to WebGPU. Escape hatch: if a CPU hot path appears (e.g., demosaic on non-GPU servers), an isolated C++/JNI kernel can be added behind the `RenderBackend` interface without touching the architecture.

---

## 2. Technology Evaluation

| Concern | Chosen | Evaluated alternatives | Rationale |
|---|---|---|---|
| UI | Jetpack Compose | XML Views | Modern, testable, required by spec; camera preview embedded via `AndroidExternalSurface`/`SurfaceView` interop. |
| Camera | CameraX + Camera2 interop | Pure Camera2 | CameraX handles device quirks/lifecycle; `Camera2CameraControl`/`CaptureRequestOptions` interop exposes every manual control we need (ISO, exposure time, focus distance, AWB lock). Pure Camera2 costs months of per-device QA for no extra capability. |
| GPU API | **OpenGL ES 3.1** primary; Vulkan behind interface, deferred | Vulkan-first; RenderScript (dead); AGSL-only | GLES 3.1 = compute shaders + 16F render targets on ~every 2018+ device. Vulkan gives explicit memory + better multi-pass batching but triples driver-bug surface. AGSL/RenderEffect cannot express multi-pass pipelines (no intermediate FBO control) — used only for cheap UI-layer effects (blur behind panels). |
| Shading language | GLSL ES 310, single source | Per-backend shaders | One `.glsl` library; Vulkan backend later consumes the same source via glslang→SPIR-V. |
| DI | Hilt (app), constructor injection (engine) | Koin | Engine modules stay framework-free: plain constructors + a small factory. Hilt only in Android shells. |
| Local DB | Room + JSON columns for graphs | SQLDelight | Room is required by spec; SQLDelight noted as the KMP migration path (schema kept portable: no Room-specific types in entities). |
| Backend framework | **Ktor** | Spring Boot, Node | One language across the monorepo; engine code (recipe validation, server-side rendering) runs in-process on the backend. Spring is heavier and adds a second idiom for no gain at our scale. |
| Backend DB | PostgreSQL + Redis cache | Mongo | Relational fits recipes/versions/social graph; JSONB for recipe payloads gives schema flexibility where needed. |
| Object storage | S3-compatible (MinIO dev / cloud prod) | DB blobs | Previews, LUTs, packs are immutable blobs behind a CDN. |
| RAW decode | libraw via thin JNI + Camera2 DngCreator for capture | Pure-Kotlin decoder | Writing a RAW decoder is a company-sized project; libraw is battle-tested. Isolated behind `RawDecoder` interface. |
| CV tasks | OpenCV only where irreplaceable (lens calibration tooling, future subject masks) | OpenCV everywhere | OpenCV in the hot path kills the GPU-first goal; it is a *tooling and future-AI* dependency, not a pipeline dependency. |

---

## 3. Module Breakdown (Monorepo)

```
filmengine/
├── core/
│   ├── image-engine/        # node graph, scheduler, buffers, tile manager
│   ├── color-science/       # color spaces, matrices, transfer fns, CAT, ΔE
│   ├── film-engine/         # film stock models, density, grain, halation
│   ├── recipe-engine/       # .filmrecipe container, versioning, migration
│   ├── device-profiles/     # sensor/lens/noise profile model + loader
│   └── plugin-sdk/          # public plugin API surface (pure interfaces)
├── render/
│   ├── gpu-renderer/        # GLES 3.1 backend (android + desktop GL)
│   ├── cpu-renderer/        # reference backend (correctness, tests, server)
│   └── shaders/             # shared GLSL library (versioned)
├── android/
│   ├── camera-core/         # CameraX/Camera2 session, controls, capture
│   ├── camera-app/          # app shell: DI, navigation
│   ├── feature-camera/      # viewfinder UI, controls, scopes
│   ├── feature-editor/      # layer editor UI
│   ├── feature-library/     # gallery, versions
│   ├── feature-community/   # recipe browsing/sharing UI
│   └── data-local/          # Room, MediaStore, WorkManager jobs
├── backend/
│   ├── backend-api/         # Ktor app: routing, auth, modules-as-services
│   ├── backend-processing/  # server-side render workers (uses core + cpu/gpu-renderer)
│   └── backend-db/          # migrations (Flyway), query layer
├── desktop/
│   ├── desktop-viewer/      # Compose Desktop shell
│   └── cli-renderer/        # headless batch renderer
├── tooling/
│   ├── profile-calibrator/  # chart-based device calibration tool
│   ├── film-lab/            # stock-model fitting from film scans
│   └── benchmark/           # macro/microbenchmark harness
└── docs/
```

**Dependency rules (enforced by Gradle `api`/`implementation` + Konsist tests):**
- `core/*` depends only on Kotlin stdlib + kotlinx (coroutines, serialization, datetime).
- `render/*` depends on `core/image-engine` only.
- `android/*` may depend on anything above it; nothing depends on `android/*`.
- `plugin-sdk` is the *only* module plugins may compile against.

---

## 4. Package & Folder Structure

Convention: `app.filmengine.<module>.<layer>`.

```
core/image-engine/src/commonMain/kotlin/app/filmengine/engine/
├── graph/        # ProcessGraph, ProcessNode, Port, ParamSpec, GraphCompiler
├── node/         # built-in node implementations (metadata + param schema)
├── buffer/       # ImageBuffer, PixelFormat, TileGrid, BufferPool contract
├── exec/         # ExecutionPlan, Scheduler, RenderBackend interface
├── cache/        # node-output cache, invalidation (dirty propagation)
└── api/          # public façade: Engine, RenderRequest, RenderResult

android/feature-camera/src/main/kotlin/app/filmengine/camera/
├── presentation/ # CameraViewModel, UiState, Compose screens
├── domain/       # use cases: CapturePhoto, SetManualExposure, ...
└── di/

backend/backend-api/src/main/kotlin/app/filmengine/backend/
├── auth/  recipes/  community/  sync/  packs/  jobs/   # one package = one service boundary
└── platform/     # shared: db, redis, storage, errors, versioning
```

Each Android feature module: `presentation / domain / data / di`. Each backend service package: `routes / service / repo / dto`.

---

## 5. Image Processing Engine

### 5.1 Core model — node graph, not a chain

The pipeline is a **directed acyclic graph** of `ProcessNode`s. A "chain" is just the common degenerate case. Nodes are *descriptions* (id, type, params, enabled); execution is separate.

```kotlin
// Conceptual shape (not implementation)
ProcessGraph
 ├─ nodes: List<NodeInstance>          // type, params, enabled, id
 ├─ edges: List<Edge>                  // outputPort -> inputPort
 └─ meta:  colorSpaceContract per edge // linear/log/display, gamut

NodeDescriptor                          // registered per node type
 ├─ paramSchema: List<ParamSpec>       // typed, ranged, animatable flag
 ├─ ioSpec: ports + accepted color states
 ├─ stage: DEVELOP | CREATIVE | OUTPUT # reorderability class
 └─ kernels: { gpu: ShaderRef, cpu: KernelRef, ai?: ModelRef }
```

Key properties:

- **Color-state typing.** Every edge carries a color state (`SceneLinear(Rec2020)`, `Log(FilmLog)`, `Display(sRGB)`). The `GraphCompiler` rejects graphs that feed display-referred data into a scene-referred node and auto-inserts conversion nodes where safe. This single rule prevents the #1 class of image-quality bugs.
- **Reorderability classes.** `DEVELOP` nodes (linearization → demosaic → NR) have a physically fixed order; `CREATIVE` nodes (film sim, grain, bloom, split tone…) are freely reorderable; `OUTPUT` is terminal. The UI exposes reordering only within `CREATIVE`.
- **Three backends per node:** GPU (production), CPU (reference/server), AI (future — a node kernel type, not a separate system). CPU and GPU implementations are parity-tested (§20).

### 5.2 Compilation & execution

```
ProcessGraph ──GraphCompiler──▶ ExecutionPlan ──Scheduler──▶ RenderBackend
              · topological sort            · pass batching
              · dead-node elimination       · FBO/buffer assignment
              · PASS FUSION (see below)     · tile iteration (export)
```

> **Decision D2 — Pass fusion via 3D-LUT baking.**
> All *pointwise* color nodes between two spatial nodes (curves, matrices, WB, HSL, split tone, film color) are **fused at compile time into a single 33³ RGBA16F 3D LUT** applied in one shader pass. Spatial nodes (blur, bloom, grain, sharpen, halation) remain individual passes.
> **Alternatives:** run every node as its own pass (simple, but 30 nodes = 30 fullscreen passes → nowhere near 60 FPS); runtime shader stitching (Darktable/Metal-style — powerful but a shader-compiler project of its own).
> **Why:** this is how Resolve/Dehancer-class tools hit real-time: a 30-node graph typically compiles to **5–8 GPU passes**. LUT re-bake on param change costs ~35k shader evaluations (sub-millisecond on GPU) and is itself done in a compute pass. Precision caveat: LUT fusion is only applied in perceptually-uniform-ish spaces (log), never across a linear→log boundary with extreme dynamic range, to avoid interpolation banding; the compiler knows which nodes are "fusable-safe."

### 5.3 Buffers, precision, memory

- Working format: **RGBA16F** everywhere on GPU (scene-referred linear needs >8 bits; 32F only for the raw-develop stages where sensor data demands it).
- **BufferPool:** all intermediate textures come from a size-bucketed pool; zero allocations in steady-state preview.
- **Tile-based export:** full-res export renders in 512×512 tiles with N-pixel overlap (N = max spatial kernel radius in the graph, computed at compile time). Enables 100MP+ output on 6GB devices.
- **Incremental rendering / dirty propagation:** each node output is cached keyed by `(nodeId, paramsHash, upstreamHash)`. Editing "grain amount" re-executes only grain-and-downstream — upstream develop results are reused. This is what makes scrubbing sliders feel instant.

### 5.4 Full pipeline (default develop graph)

```
RAW sensor data (Bayer, 10–14 bit)
 ├─ Linearization (sensor transfer fn from device profile)
 ├─ Black level subtraction + white level normalize      [32F]
 ├─ Lens correction (distortion, vignetting, CA — profile-driven, GPU warp)
 ├─ White balance (channel gains, pre-demosaic)
 ├─ Demosaic (GPU: MHC bilinear→AHD-lite quality ladder; preview uses fast path)
 ├─ Noise profiling (per-ISO variance model from device profile → NR params)
 ├─ Noise reduction (GPU NLM-lite luma + chroma, wavelet chroma option)
 ├─ Exposure (linear gain)
 ├─ Highlight reconstruction (channel-ratio + soft rolloff)
 ├─ Shadow lift (scene-linear, before tone map)
 ├─ Local contrast (guided-filter base/detail split)
 ├─ Tone mapping (parametric filmic spline — the engine's "look neutral" default)
 ├─ RGB curves ──┐
 ├─ Color matrix │  ← typically FUSED into one LUT pass (D2)
 ├─ Camera profile (device color → working space)
 ├─ FILM SIMULATION (film-engine subgraph, §6) ──┘
 ├─ Grain · Halation · Bloom · Glow · Lens diffusion   [spatial passes]
 ├─ Chromatic aberration (creative) · Lens blur (depth-aware later)
 ├─ Split toning · Color balance ── fused
 ├─ Texture · Clarity · Sharpen (unsharp on luma)      [spatial]
 ├─ Vignette · Dust · Scratches (procedural + scanned overlays)
 ├─ Export transform (working → sRGB/Display-P3, dither, resize)
 └─ Encode: JPEG / HEIF (platform codec) / DNG passthrough
```

Every stage = one node: independently enable/disable-able, parameterized, and (in `CREATIVE`) reorderable.

---

## 6. Film Simulation Engine (`film-engine`)

**Research grounding.** Film response is characterized by: (1) the **H&D characteristic curve** — density vs log exposure, with toe (shadows) and shoulder (highlight roll-off) — which is why film "never clips" the way digital does; (2) **spectral dye coupling** — each emulsion layer's dye absorbs imperfectly, creating the cross-channel color character (Kodak warmth, Fuji greens); (3) **grain** that is multiplicative-ish, strongest in midtones, larger in shadows, and *colored* in negative film; (4) **halation** — red-orange glow around speculars from light reflecting off the film base (CineStill 800T's signature, because its remjet layer is removed). Dehancer's differentiator is modeling these *physically in log/density space* rather than baking a LUT. That is the architecture we adopt.

> **Decision D3 — A film stock is a parametric model evaluated in log-density space, not a LUT.**
> **Alternatives:** 3D LUT per stock (HALD images) — cheap but exposure-variant (a LUT bakes one exposure's response; push/pull and highlight behavior break), and unforkable by users.
> **Why:** parametric stocks respond correctly to exposure changes, expose meaningful controls (push/pull, halation strength), compress to ~2–10 KB per stock, and are *diffable/forkable* — which the entire recipe community feature depends on. LUTs remain supported as an *import* node for third-party looks.

**FilmStock model** (the subgraph the film-sim node expands to):

```
working linear ─▶ to FilmLog (log exposure)
  ├─ Exposure/DIR coupling  (push/pull, inter-layer inhibition ≈ saturation vs density)
  ├─ Characteristic curves  (per-channel spline: toe, linear slope=gamma, shoulder)
  ├─ Dye matrix             (3×3 + per-hue HSL table = color separation/crosstalk)
  ├─ Color chrome           (chroma-dependent density boost — Fuji "Color Chrome Effect")
  ├─ Density controls       (overall + per-channel density trim)
  ├─ Halation               (threshold speculars → red-weighted blur → screen blend) [spatial]
  ├─ Bloom / diffusion      (mist filter model) [spatial]
  ├─ Grain                  (procedural: per-channel blue-noise field, size/roughness/
  │                          shadow-bias params, exposure-coupled amplitude; optional
  │                          scanned-grain tiles for premium packs) [spatial]
  └─ Print/paper stage (optional: 2nd characteristic curve = negative→print chain)
 ─▶ back to working linear
```

Stocks ship as data (`.filmstock` files inside packs): Fuji-style (Provia/Velvia/Astia/Classic-Chrome analogs), Kodak negative/slide analogs, Ilford B&W (with spectral sensitivity weighting for B&W conversion), CineStill (halation-forward). **Legal note:** ship analog names ("CR 200" not "Classic Chrome") — trademarked names are a real commercial risk.

The `film-lab` tool fits stock parameters from datasheet curves + film scan/target pairs (least-squares on the H&D spline and dye matrix) — this is how the catalog is built without hand-tuning.

---

## 7. Layer-Based Editing

Layers are the **user-facing projection of the node graph** — not a second system.

```
EditSession
 └─ LayerStack: [Layer(type=FilmSim, params, enabled, opacity, blend, mask?)]
        │  compile (pure function)
        ▼
   ProcessGraph  ──▶ engine
```

- Each layer = one node (or one subgraph, e.g. film sim). Reorder layer ⇒ reorder node.
- **Opacity/blend:** implemented generically — the compiler wraps any layer as `mix(input, layerOutput, opacity)` with blend modes (normal, screen, overlay, luminosity, color) as a standard wrapper shader. Every layer gets blending for free.
- **Masks (future-proofed now):** `Layer.mask: MaskRef?` exists in the schema from v1 (radial/linear/luma-range first; AI semantic masks later plug in as another `MaskSource`). Shipping the *field* now avoids a migration; shipping the *feature* waits.
- Layer presets, copy/paste layers between images, and "flatten to recipe" are pure graph operations.

---

## 8. Non-Destructive Editing & Versioning

> **Decision D4 — Originals are immutable; edits are stored as serialized graphs; version history = append-only snapshots of the graph.**
> **Why snapshots not op-log:** a graph serializes to 1–10 KB JSON. At that size, an operational log (undo/redo deltas, CRDTs) is over-engineering — snapshot-per-save gives perfect history, trivial "restore version," and no replay bugs. In-session undo/redo is an in-memory stack, not persisted per keystroke.

Storage model (Room):

```
Photo        (id, mediaStoreUri, rawUri?, capturedAt, deviceProfileId, exif…)
EditSession  (id, photoId, currentGraphJson, updatedAt)
EditVersion  (id, sessionId, graphJson, label, createdAt)   -- append-only
RecipeLink   (sessionId, recipeId, recipeVersion)           -- provenance
```

Originals live in MediaStore (RAW as DNG, JPEG as captured) and are never rewritten. Exports are new MediaStore entries. "Return months later, continue losslessly" falls out of this for free. Graph JSON embeds the engine schema version; the recipe-engine migrator (§9) upgrades old graphs on load.

---

## 9. Recipe Engine & `.filmrecipe` Format

> **Decision D5 — `.filmrecipe` is a ZIP container with a JSON manifest — the DOCX/ORA pattern.**
> **Alternatives:** bare JSON (can't carry previews/LUTs/grain assets), protobuf container (opaque to users and to the community-diff features), custom binary (all cost, no benefit).
> **Why:** ZIP gives packaging, partial reads (preview without full parse), forward-compatible extra entries, and universal tooling. JSON manifest keeps recipes human-diffable — which powers fork/version UX.

```
my-recipe.filmrecipe (ZIP)
├── manifest.json      # format_version (semver), engine_min_version, id (UUID),
│                      # name, author, description, tags, parent_recipe_id (fork lineage),
│                      # created/updated, signature?
├── graph.json         # the ProcessGraph: nodes, params, order, layers
├── preview.jpg        # 1024px preview
├── thumb.jpg          # 256px
├── assets/
│   ├── *.cube         # optional imported LUTs
│   ├── *.filmstock    # embedded custom stock models
│   └── grain/*.png    # optional scanned grain tiles
└── checksums.json     # sha256 per entry
```

**Versioning & compatibility rules:**
- `format_version` is semver. Readers accept same-major, warn on newer-minor (unknown nodes render as "missing node" pass-through — graph still loads), reject newer-major with an upgrade prompt.
- Migrations are forward-only pure functions `graph vN → vN+1`, chained; covered by golden-file tests with real recipes from every historical version.
- `parent_recipe_id` + backend version table give full fork/lineage graphs for the community.
- Optional Ed25519 `signature` (publisher signing) for marketplace packs (§17).

`.filmpack` = ZIP of recipes/stocks + pack manifest. Same machinery.

---

## 10. Camera Architecture (`camera-core` + `feature-camera`)

```
                 CameraX (lifecycle, device quirks)
                      │ Camera2 interop for manual control
   ┌──────────────────┼──────────────────────────────┐
   ▼                  ▼                              ▼
Preview          ImageAnalysis                 Capture
SurfaceTexture   (低-res YUV, scopes/AF aid)   ImageCapture(JPEG/HEIF)
(OES texture)                                  + Camera2 RAW → DngCreator
   │                                                │
   ▼                                                ▼
GL pipeline: OES → working space → PREVIEW GRAPH → display surface
                                    (film sim live @ 60fps)        full graph
                                                                   via WorkManager
```

- **Live film simulation** = the same engine, running a *preview-quality* compilation of the same graph (lower LUT resolution, cheaper grain, no tiling) directly on the camera OES texture. One graph definition, two compilation profiles — preview and export can never drift apart visually except in defined quality knobs.
- **Manual controls** map to Camera2 interop: `SENSOR_SENSITIVITY` (ISO), `SENSOR_EXPOSURE_TIME` (shutter), `LENS_FOCUS_DISTANCE` (MF), AWB modes + `COLOR_CORRECTION_GAINS` for WB shift, AE/AF lock triggers, `CONTROL_AE_EXPOSURE_COMPENSATION`. Shutter/Aperture priority are implemented in a small deterministic **ExposureController** state machine (auto-solves the free variables from the metered EV); aperture priority is simulated on fixed-aperture hardware (selects lens/adds compensation) and real on dual-aperture devices.
- **Scopes** (histogram, RGB histogram, waveform, vectorscope, zebra, false color, focus peaking) run on a **¼-res copy of the preview texture via GLES compute shaders** (atomic-add histograms), rendered as overlay quads. Budget: <1.5 ms total; scopes are toggled off automatically if frame budget is exceeded (perf guard, §16).
- **Capture modes:** single (JPEG/HEIF/RAW/RAW+JPEG), timer, burst (CameraX + ZSL where supported), exposure bracketing (Camera2 sequence). Bracketed HDR fusion is a *pipeline node* (future computational feature), not camera code.
- Multi-lens: `CameraSelector` per physical lens + logical zoom; macro mode = closest-focus lens selection + MF assist.

Photos capture pipeline: RAW/JPEG lands in MediaStore immediately (never lose a shot), then a WorkManager job runs the full-quality graph render for the in-app "processed" preview. Camera stays responsive; processing is asynchronous and battery-aware.

---

## 11. GPU Architecture (`gpu-renderer`)

```
RenderBackend (interface, in image-engine)
 ├─ GlesBackend   (v1: Android + desktop GL 4.x via same GLSL)
 ├─ VulkanBackend (v2: perf headroom, ahead-of-time pipelines)
 └─ CpuBackend    (reference)

GlesBackend internals:
 ├─ RenderGraph executor: pass list from ExecutionPlan, FBO pool, barrier-free
 │   ping-pong scheduling
 ├─ ShaderLibrary: versioned GLSL modules + #include preprocessor,
 │   program cache keyed by (shader, defines), async compile at app start
 ├─ LutBaker: compute pass that evaluates fused color stack into 33³ 3D texture
 ├─ Samplers/formats: RGBA16F color, R16F luma aux, RG16F flow (future)
 └─ Query timers per pass → PerfMonitor (per-node GPU ms in debug HUD)
```

Per-effect GPU strategy (the spatial passes that survive fusion):

| Effect | Implementation |
|---|---|
| Bloom / Glow | Threshold → dual-Kawase blur pyramid (cheapest quality/ms ratio) → tinted screen-blend upsample |
| Halation | Same pyramid, red-orange weighted threshold, applied *pre-tone-map* in film subgraph |
| Grain | Procedural value-noise + blue-noise texture tiles, luma-coupled amplitude; 1 pass |
| Lens diffusion | Small-sigma Gaussian mix (mist model), shares pyramid with bloom when both active |
| Blur (lens) | Scatter-as-gather disc blur, quality tiers; depth-aware later |
| Local contrast / clarity | Guided filter (2 passes) base/detail split |
| Sharpen | Unsharp mask on luma, 1 pass |
| NR | Bilateral/NLM-lite luma + separable chroma blur in YCbCr, 2–3 passes |
| Scopes | Compute shader atomics into SSBO, drawn as instanced quads |

**Frame budget @60 FPS = 16.6 ms:** camera latch ~1 ms · develop-lite ~3 ms · fused LUT pass ~1.5 ms · spatial passes ~6 ms · scopes ~1.5 ms · UI ~2 ms · headroom ~1.5 ms. Enforced by PerfMonitor with automatic quality degradation ladder (drop grain quality → drop scope rate → half-res spatial passes) before ever dropping frames.

---

## 12. Backend Architecture

> **Decision D6 — Modular monolith with hard service boundaries, split later.**
> **Alternatives:** day-one microservices per the service list.
> **Why:** at launch scale (<1M users), microservices buy latency, ops burden, and distributed-transaction pain with zero benefit. Each service is a Ktor module with its own package, own DB schema (Postgres schemas), own API prefix, and *no cross-module imports except via interfaces* — so any module can be extracted to its own deployment when metrics demand it. The processing service is the exception: **it is separate from day one** (different scaling profile: CPU/GPU render workers, queue-driven).

```
                    ┌── CDN ──► object storage (previews, packs, LUTs)
clients ──► API GW ─┤
                    └─► backend-api (Ktor, stateless, N replicas)
                          ├─ auth       (JWT access + rotating refresh; OAuth-ready)
                          ├─ users      (profiles, follows)
                          ├─ recipes    (CRUD, versions, forks, search)
                          ├─ community  (comments, likes, ratings, feeds)
                          ├─ packs      (marketplace, entitlements)
                          ├─ sync       (per-user changefeed, cursor-based)
                          └─ notifications (fan-out via Redis pub/sub → FCM)
                          │
                          ├── PostgreSQL (primary + read replica)
                          ├── Redis (sessions, hot feeds, rate limits, queues)
                          └── job queue ──► backend-processing workers
                                            (server-side renders, preview gen,
                                             pack validation, virus/zip-bomb scan)
```

- **API versioning:** URL-prefixed (`/v1/...`); additive changes within a version, breaking changes = new version, two majors supported concurrently.
- **Sync protocol:** offline-first clients push local mutations with client-generated UUIDs + `updated_at`; server resolves last-writer-wins per field for profile data, and **never merges recipes** — a conflicting recipe edit creates a new version (versions are cheap and the domain is version-native).
- **Uploads:** client → presigned PUT to object storage → notify API → processing worker validates container (schema, checksums, zip-bomb limits, image re-encode) before the recipe becomes visible.

---

## 13. Database Schema (PostgreSQL, abridged DDL)

```sql
users            (id uuid PK, email citext UNIQUE, handle citext UNIQUE,
                  display_name, avatar_key, bio, created_at, status)
auth_credentials (user_id FK, password_hash, mfa_secret?, provider, provider_id)

recipes          (id uuid PK, owner_id FK, slug, name, description, tags text[],
                  parent_recipe_id FK NULL,        -- fork lineage
                  head_version_id FK, visibility enum(private,unlisted,public),
                  downloads int, likes int, rating_avg numeric, rating_count int,
                  created_at, updated_at)          -- counters denormalized, rebuilt async
recipe_versions  (id uuid PK, recipe_id FK, version int, format_version text,
                  manifest jsonb, graph jsonb,     -- payload queryable
                  package_key text,                -- object storage: .filmrecipe
                  preview_key, changelog, created_at,
                  UNIQUE(recipe_id, version))

film_packs       (id, owner_id, name, description, price_cents?, package_key, status)
pack_items       (pack_id FK, recipe_id FK, position)
entitlements     (user_id, pack_id, granted_at, source enum(purchase,promo,bundle))

favorites        (user_id, recipe_id, created_at, PK(user_id, recipe_id))
ratings          (user_id, recipe_id, stars smallint CHECK 1..5, PK(user_id, recipe_id))
comments         (id, recipe_id FK, user_id FK, parent_id FK NULL, body, created_at,
                  deleted_at)                      -- soft delete, thread-capable
follows          (follower_id, followee_id, created_at, PK(follower_id, followee_id))
downloads        (id bigserial, recipe_version_id FK, user_id FK NULL, created_at)
                                                   -- partitioned by month

device_profiles  (id, device_model, sensor_id, profile_version int, payload jsonb,
                  published_at)                    -- OTA profile updates = new rows
image_metadata   (id, user_id, client_photo_id uuid, exif jsonb, sync fields)
processing_jobs  (id, type, status enum(queued,running,done,failed), payload jsonb,
                  attempts, created_at, finished_at, worker_id)
plugins          (id, publisher_id, kind enum(content,code), name, status)
plugin_versions  (plugin_id FK, version, min_app_version, package_key, signature,
                  review_status)
sync_log         (user_id, seq bigserial, entity_type, entity_id, op, at)
                                                   -- per-user changefeed for cursor sync
```

Indexes: recipes GIN on `tags`, trigram on `name`; feeds served from Redis with Postgres as source of truth.

---

## 14. REST API Specification (v1, representative)

```
POST   /v1/auth/register | /login | /refresh | /logout
GET    /v1/users/me                     PATCH /v1/users/me
GET    /v1/users/{handle}               POST/DELETE /v1/users/{id}/follow

GET    /v1/recipes?query=&tags=&sort=trending|new|top&cursor=
POST   /v1/recipes                      # create (metadata; package via upload flow)
GET    /v1/recipes/{id}                 PATCH/DELETE /v1/recipes/{id}
GET    /v1/recipes/{id}/versions        POST /v1/recipes/{id}/versions
GET    /v1/recipes/{id}/versions/{n}/download   # 302 → CDN, logs download
POST   /v1/recipes/{id}/fork            # server-side lineage copy
POST/DELETE /v1/recipes/{id}/like       PUT /v1/recipes/{id}/rating
GET/POST /v1/recipes/{id}/comments

GET    /v1/packs?…                      POST /v1/packs      GET /v1/packs/{id}
POST   /v1/uploads                      # returns presigned PUT + upload_id
POST   /v1/uploads/{id}/complete        # triggers validation job

GET    /v1/sync/changes?cursor=         # per-user changefeed
POST   /v1/sync/push                    # batched client mutations

GET    /v1/profiles/devices/{model}     # latest device profile (OTA)
GET    /v1/feed                         # followed creators + trending
```

Conventions: cursor pagination everywhere (`cursor`/`next_cursor`), RFC 7807 problem+json errors, `Idempotency-Key` honored on all POSTs, rate limits per token + per IP (Redis token bucket).

---

## 15. Plugin Architecture

> **Decision D7 — Two plugin tiers; declarative content plugins ship first, code plugins are a later, signed, reviewed tier.**
> **Why:** 90% of the plugin value (film packs, LUT packs, camera profiles, grain libraries, export presets) is *data*, which is trivially safe to sandbox — it's parsed, validated, never executed. Executable plugins on Android mean DEX loading: a real security/Play-policy minefield that must be gated behind signing + review, not shipped in v1.

- **Tier 1 — Content plugins:** `.filmpack` containers registered via `plugin-sdk` schemas. New stocks, recipes, LUTs, grain scans, device profiles, export presets. Installed = rows in a local registry + assets on disk. Zero code execution.
- **Tier 2 — Code plugins (post-v1):** implement `plugin-sdk` interfaces (`ProcessNodePlugin` — param schema + GLSL source + CPU kernel; `ExportFormatPlugin`; `CloudProviderPlugin`; `AiModulePlugin`). Distributed as signed packages; GLSL is compiled by *our* runtime (shader source is data, safely sandboxable — this is the loophole that lets third parties ship custom *effects* without executing their JVM code); JVM-code plugins require marketplace review + signature verification, loaded in an isolated process where feasible.
- **Core independence:** the app discovers plugins via the registry; node types, stocks, and export formats are all looked up by string ID at graph-compile time. Installing a plugin never modifies core modules — the registry *is* the extension point.
- SDK versioning: `plugin-sdk` follows semver; the manifest declares `min_sdk`/`target_sdk`; CI runs an API-compatibility check (binary-compat validator) on every `plugin-sdk` change.

---

## 16. Device Profiles & Calibration

`DeviceProfile` (JSON payload, OTA-updatable via `/v1/profiles/devices`):

```
sensor:  black/white levels per ISO, transfer fn, base color matrices (2 illuminants,
         D65 + StdA, interpolated by estimated CCT — the DNG dual-illuminant model),
         forward matrix → working space
noise:   per-ISO luma/chroma variance curves (drives NR defaults)
lens[]:  per physical lens — distortion (Brown-Conrady k1..k3), vignetting falloff,
         lateral CA coefficients, focus breathing hint
wb:      AWB bias calibration per illuminant
```

- **Calibration methodology** (`tooling/profile-calibrator`): ColorChecker capture set across illuminants → solve 3×3 matrices (linear least squares in XYZ, ΔE₀₀-weighted); flat-field frames → vignette falloff; dot-grid → distortion; dark frames across ISO ladder → noise model. Same method pro RAW converters use.
- Runtime: profile selected by `Build.MODEL` + CameraCharacteristics sensor ID; falls back to the device's own DNG metadata (Camera2 reports `SENSOR_COLOR_TRANSFORM`) when no first-party profile exists — so unknown devices still work, just less beautifully.
- Profiles are versioned rows server-side; the app checks for updates opportunistically (WorkManager, unmetered).

---

## 17. Security Strategy

- **Auth:** short-lived JWT access (15 min) + rotating refresh tokens (revocable, stored hashed); Argon2id password hashing; OAuth providers pluggable. Tokens in EncryptedSharedPreferences/Keystore on device.
- **Content pipeline is the main attack surface:** uploaded `.filmrecipe`/`.filmpack` are parsed **only** in sandboxed processing workers — zip-bomb limits (entry count, per-entry + total decompressed size), schema validation, image re-encoding (never serve user-uploaded bytes as previews), path-traversal rejection (`../` entries), checksum verification.
- **Client-side import** applies the same container validation (shared `recipe-engine` validator — one implementation, both sides).
- **Marketplace integrity:** publisher packs signed (Ed25519); app verifies signatures before install; code plugins additionally require review status server-side.
- **Privacy:** EXIF GPS stripped by default on any upload/share (opt-in to keep); analytics anonymized + consent-gated; recipes contain *no* image data unless the user explicitly attaches a preview.
- **API:** rate limiting, `Idempotency-Key`, strict input validation at every trust boundary (DTO layer), least-privilege DB roles per service module, presigned-URL uploads so image bytes never transit the API tier.
- **Play integrity:** optional Play Integrity API check for marketplace purchases only (never gate core photography).

---

## 18. Performance Strategy

The four pillars, in priority order:

1. **Do less work:** pass fusion (D2), dirty-node caching (§5.3), preview-vs-export compilation profiles, scopes on ¼-res, half-res spatial passes on preview when over budget.
2. **Never allocate in the loop:** BufferPool for textures/FBOs, preallocated param UBOs, zero-GC frame path (verified by Allocation Tracker in CI macrobenchmark).
3. **Right hardware for the job:** GPU-first for pixels; CPU (coroutines on `Dispatchers.Default`) only for graph compilation, IO, codecs; capture/encode on dedicated dispatcher; camera frame latch on GL thread.
4. **Measure, don't guess:** per-pass GPU timers, Macrobenchmark suite (cold start, preview jank, capture-to-gallery latency, export throughput), Perfetto traces in CI on physical device farm; regression gates (>10% = red build).

Additional: tile-based export with overlap (§5.3); incremental thumbnail invalidation in library; battery guard (thermal status listener drops preview to 30 FPS + disables scopes on `THERMAL_STATUS_SEVERE`); JPEG/HEIF encode via hardware codec.

Targets: 60 FPS preview with film sim on 2022+ flagships (degradation ladder below that), <600 ms capture-to-shutter-ready, <4 s 48MP full-graph export, <250 MB peak RSS in camera, zero jank at P95 slider scrub.

---

## 19. Testing Strategy

| Layer | Approach |
|---|---|
| color-science | Pure unit tests against published data: matrix round-trips, ΔE₀₀ vs reference values, transfer-fn inverses (`f⁻¹(f(x)) < 1e-5`) |
| image-engine | Graph compiler tests (fusion correctness, color-state rejection, dead-node elim); property tests (enable/disable idempotence, reorder within CREATIVE = deterministic) |
| Kernels | **CPU/GPU parity:** every node's GPU output vs CPU reference within per-node tolerance on a synthetic + real image corpus. This is the single highest-value test suite in the project. |
| Golden images | Full-graph renders vs stored goldens (perceptual diff, not byte-diff — tolerance for GPU vendor variance); goldens regenerated only via reviewed script |
| recipe-engine | Round-trip (write→read = identity); migration chain vN→head for every historical fixture; malicious-container corpus (zip bombs, traversal) |
| camera-core | Instrumented on device farm (FTL): control application, capture modes; ExposureController as pure-logic unit tests |
| Android UI | Compose UI tests for critical flows; screenshot tests (Paparazzi) for controls/scopes overlays |
| Backend | Testcontainers (Postgres/Redis) service tests; API contract tests generated from OpenAPI spec, run against client DTOs too |
| Performance | Macrobenchmark + per-pass GPU-time budgets as assertions |
| Architecture | Konsist rules: dependency direction, no Android imports in `core/*`, layer conventions |

Coverage goal: `core/*` ≥ 90% line/branch (it's pure logic — cheap to test, catastrophic to break); shells pragmatic.

---

## 20. Scalability Strategy

**Client:** engine scales down (quality ladders, tiling) to mid-range devices and up (Vulkan backend, 10-bit output) without graph changes; feature modules allow app slimming (camera-only build flavor possible).

**Backend:** stateless API replicas behind LB; Postgres read replicas for feed/browse; Redis for hot paths; downloads/packs on CDN (they're immutable — cache forever, content-addressed keys); `downloads` table partitioned; processing workers autoscale on queue depth. Extraction order when needed (per-module metrics decide): processing (already separate) → community/feeds (read-heavy) → sync. Search: Postgres FTS/trigram until it hurts, then OpenSearch — not before.

**Organizational:** monorepo + module ownership; `plugin-sdk` and `.filmrecipe` format are the two forever-stable contracts — everything else may churn.

---

## 21. Developer Experience

- **Conventions:** Kotlin official style + detekt/ktlint gates; ADRs (`docs/adr/`) required for cross-module decisions; every module has a `README.md` stating its purpose and its dependency contract.
- **CI/CD:** GitHub Actions — per-module affected-target builds (Gradle build cache), unit+parity tests on every PR, device-farm instrumented + macrobenchmark nightly, backend deploys via containers (staging auto, prod gated), Play internal track on `main`, shader compilation smoke-test across Adreno/Mali/Xclipse in nightly farm run.
- **Docs:** engine API docs (Dokka), `.filmrecipe` format spec as a standalone public document (it's an ecosystem asset), plugin SDK guide with a sample pack + sample node plugin.
- **Benchmark harness** (`tooling/benchmark`): reproducible image corpus + per-node and full-graph timing, runnable locally and in CI.

---

## 22. Roadmap (milestone-gated, each shippable)

| M | Deliverable | Exit criteria |
|---|---|---|
| **M0** (6–8 wk) | Foundations: monorepo, CI, `color-science`, `image-engine` graph+compiler, CPU backend, golden-test harness | Graph renders on CPU, parity harness green |
| **M1** (8 wk) | GPU backend + camera MVP: GLES renderer, pass fusion, CameraX preview through engine, JPEG capture, 3 film stocks live | 60 FPS preview w/ film sim on reference device |
| **M2** (8 wk) | Pro camera: full manual controls, scopes, RAW (DNG), device profile v1 for 3 reference devices, develop pipeline complete | RAW→export matches golden quality bar |
| **M3** (8 wk) | Editor: layer system, non-destructive sessions, version history, export pipeline (tiled), HEIF | Edit→reopen→re-edit lossless |
| **M4** (6 wk) | Recipe engine: `.filmrecipe` import/export, local library, film-lab-fitted stock catalog (12+ stocks) | Round-trip + migration tests green; **public beta** |
| **M5** (10 wk) | Backend + community: auth, recipe CRUD/versions/forks, browse/like/comment/follow, sync, OTA profiles | v1 API frozen; offline-first verified |
| **M6** (8 wk) | Ecosystem: content plugins (packs), marketplace + entitlements, desktop-viewer + cli-renderer (KMP proof), Vulkan spike | Pack installed w/o app update; CLI renders recipe byte-comparable to device |
| **M7+** | AI nodes (NR, semantic masks, film matching), code-plugin tier, WASM preview | — |

---

## 23. Risks & Trade-offs

| Risk | Severity | Mitigation |
|---|---|---|
| GPU driver fragmentation (Mali/Adreno shader bugs) | High | GLES 3.1 conservative subset; nightly shader smoke-tests on farm; CPU-parity suite localizes vendor bugs fast; quality ladder as runtime fallback |
| 60 FPS + film sim on mid-range | High | Degradation ladder is a *designed* feature, not a hack; preview compilation profile; measure from M1, not M6 |
| Film stock quality is the product — parametric models may miss "the look" | High | `film-lab` fitting against real scans + blind A/B vs references each milestone; hybrid escape hatch (parametric + corrective LUT residual per stock) |
| Trademarked film names | Medium | Analog naming from day 1; legal review before marketplace |
| CameraX manual-control gaps on some OEMs | Medium | Capability detection + graceful UI hiding; Camera2 direct path reserved for flagship-tier features |
| Recipe format lock-in (public contract) | Medium | Format spec reviewed as if external; migration machinery + fixtures from v1 |
| Backend scope creep before product-market fit | Medium | Modular monolith (D6); community features flag-gated; M0–M4 have zero backend dependency |
| Code-plugin security | High | Deferred to post-v1; content-only tier ships first (D7); signing + review + process isolation when it lands |
| KMP desktop/WASM maturity | Low-Med | Core is pure Kotlin from day 1 (cheap insurance); desktop is a proof milestone, not a launch promise |

**Deliberate simplifications** (ponytail: named ceilings, upgrade paths):
- Snapshot version history, not op-log/CRDT — upgrade if collaborative editing ever ships.
- LWW sync + never-merge recipes — upgrade to field-level merge only if user complaints prove need.
- Postgres FTS, not OpenSearch — swap at scale pain, not before.
- GLES-only v1 — Vulkan lands behind the existing `RenderBackend` seam.
- Masks: schema now, feature later.

---

## 24. Decision Index

| # | Decision | Section |
|---|---|---|
| D1 | Kotlin KMP-ready core; shaders are the compute layer; no C++ v1 | §1.3 |
| D2 | Compile-time pass fusion via 3D-LUT baking | §5.2 |
| D3 | Parametric film stocks in log-density space, not LUTs | §6 |
| D4 | Immutable originals + graph snapshots for versioning | §8 |
| D5 | `.filmrecipe` = ZIP container + JSON manifest, semver + migrations | §9 |
| D6 | Modular monolith backend, processing service separate from day 1 | §12 |
| D7 | Content plugins first; signed/reviewed code plugins later | §15 |
| — | GLES 3.1 primary / Vulkan deferred; CameraX+interop; Ktor; RGBA16F working format; scene-linear Rec.2020 working space | §2, §5.3 |
