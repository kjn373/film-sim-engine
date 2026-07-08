# filmengine

Computational photography platform monorepo. Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

| Module | Purpose | May depend on |
|---|---|---|
| `core/color-science` | Color spaces, matrices, transfer functions. Pure Kotlin. | stdlib only |
| `core/image-engine` | Node graph, compiler, buffers, `RenderBackend` contract. Pure Kotlin. | color-science |
| `core/film-engine` | Parametric film stocks: characteristic curves, sensitivity/dye matrices. | image-engine |
| `core/recipe-engine` | Versioned graph JSON + hardened `.filmrecipe` ZIP container. | image-engine |
| `render/cpu-renderer` | Reference CPU backend (correctness baseline for GPU parity tests). | image-engine, film-engine |
| `render/gpu-renderer` | Desktop GL 3.3 backend (LWJGL) — same GLSL the Android GLES backend will use; parity-tested against the CPU reference. | image-engine, film-engine |
| `desktop/cli-renderer` | Headless renderer: image or test chart in, film-simulated PNG out. | film-engine, cpu-renderer |
| `tooling/film-lab` | Curve fitting: characteristic-curve params from measured film samples. | film-engine |
| `backend/backend-api` | Ktor modular monolith: auth (Argon2id + JWT/refresh), Postgres/Flyway. | — |

Build: `./gradlew test` (JDK 21).
