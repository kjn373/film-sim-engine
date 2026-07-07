# filmengine

Computational photography platform monorepo. Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

| Module | Purpose | May depend on |
|---|---|---|
| `core/color-science` | Color spaces, matrices, transfer functions. Pure Kotlin. | stdlib only |
| `core/image-engine` | Node graph, compiler, buffers, `RenderBackend` contract. Pure Kotlin. | color-science |
| `render/cpu-renderer` | Reference CPU backend (correctness baseline for GPU parity tests). | image-engine |

Build: `./gradlew test` (JDK 21).
