package app.filmengine.recipe

import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.ProcessGraph
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilmRecipeContainerTest {

    @TempDir
    lateinit var dir: Path

    private fun sampleRecipe(formatVersion: String = FilmRecipeContainer.FORMAT_VERSION) = Recipe(
        manifest = RecipeManifest(
            formatVersion = formatVersion,
            id = "0b7a2f0e-1111-2222-3333-444455556666",
            name = "Golden Hour Negative",
            author = "kjn",
            description = "Warm negative look",
            tags = listOf("warm", "negative"),
            createdAtEpochSeconds = 1_780_000_000,
        ),
        graph = ProcessGraph(
            listOf(NodeInstance("f", "film_sim", options = mapOf("stock" to "negato-400"))),
            emptyList(), "f",
        ),
        preview = byteArrayOf(1, 2, 3, 4, 5),
    )

    private fun maliciousZip(name: String, vararg entries: Pair<String, ByteArray>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(path.outputStream()).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `write-read round trip preserves everything`() {
        val path = dir.resolve("r.filmrecipe")
        val recipe = sampleRecipe()
        FilmRecipeContainer.write(path, recipe)
        val loaded = FilmRecipeContainer.read(path)
        assertEquals(recipe.manifest, loaded.manifest)
        assertEquals(recipe.graph, loaded.graph)
        assertContentEquals(recipe.preview, loaded.preview)
    }

    @Test
    fun `write is deterministic - same recipe, same bytes`() {
        val a = dir.resolve("a.filmrecipe")
        val b = dir.resolve("b.filmrecipe")
        FilmRecipeContainer.write(a, sampleRecipe())
        FilmRecipeContainer.write(b, sampleRecipe())
        assertContentEquals(a.toFile().readBytes(), b.toFile().readBytes())
    }

    @Test
    fun `tampered entry is rejected by checksum`() {
        val clean = dir.resolve("clean.filmrecipe")
        FilmRecipeContainer.write(clean, sampleRecipe())
        // Rebuild the zip with one byte of graph.json flipped, checksums untouched.
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(clean.toFile().inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entries[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val graph = entries.getValue("graph.json")
        graph[graph.size / 2] = (graph[graph.size / 2] + 1).toByte()
        val tampered = maliciousZip("tampered.filmrecipe", *entries.map { it.key to it.value }.toTypedArray())
        val ex = assertFailsWith<RecipeFormatException> { FilmRecipeContainer.read(tampered) }
        assertTrue("Checksum mismatch" in ex.message!!)
    }

    @Test
    fun `path traversal entry names are rejected`() {
        val zip = maliciousZip("evil.filmrecipe", "../evil.txt" to byteArrayOf(1))
        assertFailsWith<RecipeFormatException> { FilmRecipeContainer.read(zip) }
        val zip2 = maliciousZip("evil2.filmrecipe", "C:\\evil.txt" to byteArrayOf(1))
        assertFailsWith<RecipeFormatException> { FilmRecipeContainer.read(zip2) }
    }

    @Test
    fun `entry count and size bombs are rejected`() {
        val many = maliciousZip(
            "many.filmrecipe",
            *(1..10).map { "e$it.bin" to byteArrayOf(0) }.toTypedArray()
        )
        assertFailsWith<RecipeFormatException> {
            FilmRecipeContainer.read(many, ContainerLimits(maxEntries = 4))
        }

        val big = maliciousZip("big.filmrecipe", "huge.bin" to ByteArray(4096))
        assertFailsWith<RecipeFormatException> {
            FilmRecipeContainer.read(big, ContainerLimits(maxEntryBytes = 1024))
        }
        assertFailsWith<RecipeFormatException> {
            FilmRecipeContainer.read(big, ContainerLimits(maxTotalBytes = 1024))
        }
    }

    @Test
    fun `missing manifest or unlisted entry is rejected`() {
        val noManifest = maliciousZip(
            "nomanifest.filmrecipe",
            "graph.json" to "{}".encodeToByteArray(),
            "checksums.json" to """{"sha256":{}}""".encodeToByteArray(),
        )
        // graph.json isn't listed in checksums -> integrity failure comes first; both are rejections
        assertFailsWith<RecipeFormatException> { FilmRecipeContainer.read(noManifest) }
    }

    @Test
    fun `future container major version is rejected`() {
        val path = dir.resolve("future.filmrecipe")
        FilmRecipeContainer.write(path, sampleRecipe(formatVersion = "2.0.0"))
        val ex = assertFailsWith<RecipeFormatException> { FilmRecipeContainer.read(path) }
        assertTrue("update" in ex.message!!)
    }
}
