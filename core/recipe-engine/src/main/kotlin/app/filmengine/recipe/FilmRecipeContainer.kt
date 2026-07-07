package app.filmengine.recipe

import app.filmengine.engine.graph.ProcessGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class RecipeManifest(
    val formatVersion: String = FilmRecipeContainer.FORMAT_VERSION,
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val parentRecipeId: String? = null,
    val createdAtEpochSeconds: Long = 0,
)

class Recipe(
    val manifest: RecipeManifest,
    val graph: ProcessGraph,
    val preview: ByteArray? = null,
)

/** Read-side hardening knobs; defaults are the production limits. */
data class ContainerLimits(
    val maxEntries: Int = 64,
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
)

@Serializable
private data class Checksums(val sha256: Map<String, String>)

/**
 * .filmrecipe container: a ZIP holding manifest.json + graph.json + optional
 * preview.jpg, integrity-checked via checksums.json (ARCHITECTURE.md §9).
 * Reader is hardened: entry-count / per-entry / total decompressed-size limits,
 * path-traversal rejection, mandatory checksums, future-major rejection.
 */
// ponytail: java.util.zip (JVM-only) — swap for a multiplatform zip when core goes KMP.
object FilmRecipeContainer {
    const val FORMAT_VERSION = "1.0.0"

    private const val MANIFEST = "manifest.json"
    private const val GRAPH = "graph.json"
    private const val PREVIEW = "preview.jpg"
    private const val CHECKSUMS = "checksums.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun write(path: Path, recipe: Recipe) {
        val entries = buildMap {
            put(MANIFEST, json.encodeToString(RecipeManifest.serializer(), recipe.manifest).encodeToByteArray())
            put(GRAPH, RecipeCodec.encode(recipe.graph).encodeToByteArray())
            recipe.preview?.let { put(PREVIEW, it) }
        }
        val checksums = Checksums(entries.mapValues { (_, bytes) -> sha256(bytes) })
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val e = ZipEntry(name)
                e.time = 0 // deterministic output: same recipe -> same bytes
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            entries.forEach { (name, bytes) -> put(name, bytes) }
            put(CHECKSUMS, json.encodeToString(Checksums.serializer(), checksums).encodeToByteArray())
        }
    }

    fun read(path: Path, limits: ContainerLimits = ContainerLimits()): Recipe {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (++count > limits.maxEntries) {
                    throw RecipeFormatException("Container has more than ${limits.maxEntries} entries")
                }
                val name = entry.name
                if (name.contains("..") || name.startsWith("/") || name.contains('\\') || name.contains(':')) {
                    throw RecipeFormatException("Illegal entry name '$name'")
                }
                if (!entry.isDirectory) {
                    val bytes = readBounded(zip, limits.maxEntryBytes, name)
                    total += bytes.size
                    if (total > limits.maxTotalBytes) {
                        throw RecipeFormatException("Container exceeds ${limits.maxTotalBytes} bytes decompressed")
                    }
                    entries[name] = bytes
                }
                entry = zip.nextEntry
            }
        }

        val checksumBytes = entries[CHECKSUMS] ?: throw RecipeFormatException("Missing $CHECKSUMS")
        val checksums = try {
            json.decodeFromString(Checksums.serializer(), checksumBytes.decodeToString()).sha256
        } catch (e: Exception) {
            throw RecipeFormatException("Malformed $CHECKSUMS", e)
        }
        for ((name, bytes) in entries) {
            if (name == CHECKSUMS) continue
            val expected = checksums[name]
                ?: throw RecipeFormatException("Entry '$name' is not listed in $CHECKSUMS")
            if (sha256(bytes) != expected) {
                throw RecipeFormatException("Checksum mismatch for '$name' — container corrupted or tampered")
            }
        }

        val manifestBytes = entries[MANIFEST] ?: throw RecipeFormatException("Missing $MANIFEST")
        val manifest = try {
            json.decodeFromString(RecipeManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            throw RecipeFormatException("Malformed $MANIFEST", e)
        }
        val major = manifest.formatVersion.substringBefore('.').toIntOrNull()
            ?: throw RecipeFormatException("Bad formatVersion '${manifest.formatVersion}'")
        val supportedMajor = FORMAT_VERSION.substringBefore('.').toInt()
        if (major > supportedMajor) {
            throw RecipeFormatException(
                "Container format v${manifest.formatVersion} is newer than supported v$FORMAT_VERSION — update the app"
            )
        }

        val graphBytes = entries[GRAPH] ?: throw RecipeFormatException("Missing $GRAPH")
        return Recipe(manifest, RecipeCodec.decode(graphBytes.decodeToString()), entries[PREVIEW])
    }

    private fun readBounded(input: InputStream, max: Long, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) {
                throw RecipeFormatException("Entry '$name' exceeds $max bytes decompressed")
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
