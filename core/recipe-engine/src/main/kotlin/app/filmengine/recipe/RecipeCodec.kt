package app.filmengine.recipe

import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.ProcessGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecipeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Wire DTOs — deliberately decoupled from the engine types so either side can
// evolve behind a migration. Field names here are a public, versioned contract.
@Serializable
internal data class NodeDto(
    val id: String,
    val type: String,
    val params: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
    val options: Map<String, String> = emptyMap(),
)

@Serializable
internal data class EdgeDto(val from: String, val to: String)

@Serializable
internal data class GraphDto(
    val nodes: List<NodeDto>,
    val edges: List<EdgeDto> = emptyList(),
    val outputNodeId: String,
    val sourceState: String = ColorState.SCENE_LINEAR.name,
)

@Serializable
internal data class RecipeDocument(
    val schemaVersion: Int,
    val graph: GraphDto,
)

/** ProcessGraph <-> versioned JSON (the graph.json payload of a .filmrecipe). */
object RecipeCodec {
    const val SCHEMA_VERSION = 1

    /** Forward-only migrations: migrations[v] upgrades a v document to v+1. */
    private val migrations = emptyMap<Int, (JsonObject) -> JsonObject>()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true // same-major forward compatibility
        encodeDefaults = true    // every field name is pinned in the file
    }

    fun encode(graph: ProcessGraph): String =
        json.encodeToString(RecipeDocument.serializer(), RecipeDocument(SCHEMA_VERSION, graph.toDto()))

    fun decode(text: String): ProcessGraph {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw RecipeFormatException("Not a recipe document (invalid JSON)", e)
        }
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw RecipeFormatException("Missing schemaVersion")
        if (version > SCHEMA_VERSION) {
            throw RecipeFormatException(
                "Recipe schema v$version is newer than supported v$SCHEMA_VERSION — update the app"
            )
        }
        var current = root
        for (v in version until SCHEMA_VERSION) {
            val step = migrations[v]
                ?: throw RecipeFormatException("No migration path from schema v$v")
            current = step(current)
        }
        val doc = try {
            json.decodeFromJsonElement(RecipeDocument.serializer(), current)
        } catch (e: Exception) {
            throw RecipeFormatException("Malformed recipe document", e)
        }
        return doc.graph.toGraph()
    }

    private fun ProcessGraph.toDto() = GraphDto(
        nodes = nodes.map { NodeDto(it.id, it.type, it.params, it.enabled, it.options) },
        edges = edges.map { EdgeDto(it.from, it.to) },
        outputNodeId = outputNodeId,
        sourceState = sourceState.name,
    )

    private fun GraphDto.toGraph(): ProcessGraph {
        val state = try {
            ColorState.valueOf(sourceState)
        } catch (e: IllegalArgumentException) {
            throw RecipeFormatException("Unknown sourceState '$sourceState'")
        }
        return ProcessGraph(
            nodes = nodes.map { NodeInstance(it.id, it.type, it.params, it.enabled, it.options) },
            edges = edges.map { Edge(it.from, it.to) },
            outputNodeId = outputNodeId,
            sourceState = state,
        )
    }
}
