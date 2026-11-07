package at.jku.ssw.h5p.merge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

enum class MarkerKind(val token: String) {
    /** A chapter; its [Marker.key] is the name of the Markdown file it was built from. */
    CHAPTER("chapter"),

    /** A block; its [Marker.key] is [Markers.hashOf] of the block as it was converted. */
    BLOCK("block");

    companion object {
        fun of(token: String): MarkerKind? = entries.firstOrNull { it.token == token }
    }
}

data class Marker(val version: String, val kind: MarkerKind, val key: String)

/**
 * Marker (added to author comments metadata field of every generated block and chapter) looks like this:
 * ```
 * md2h5p:1.3:block:9a3f1c2b7d40
 * Keep line 1 so md2h5p can update this book from Markdown. The rest is yours.
 * ```
 *
 * A marker answers the only two questions the merge asks:
 *
 * - *did this block come from Markdown?* - it carries a marker.
 * - *was it changed in the editor?* - [hashOf] no longer agrees with the marker's key.
 */
object Markers {

    /**
     * Bumped when a change to [hashOf] or to the rendering would make recorded hashes mean something
     * else. A package recorded under a different version is merged normally - alignment never reads
     * the hash, only compares texts - but nothing in it is *reported* as edited in H5P, because two
     * hashes from two schemes cannot disagree meaningfully.
     */
    const val CONVERTER_VERSION = "1.3"

    private const val PREFIX = "md2h5p"

    private const val NOTE = "Keep line 1 so md2h5p can update this book from Markdown. The rest is yours."

    private const val FIELD = "authorComments"

    /**
     * Writes a marker onto every chapter and every direct child of a chapter.
     *
     * [sourceFiles] is one file name per chapter, in chapter order.
     */
    fun inject(book: JsonObject, sourceFiles: List<String>): JsonObject {
        val chapters = book["chapters"]?.jsonArray.orEmpty().mapIndexed { index, chapter ->
            val obj = chapter.jsonObject
            val entries = obj.contentEntries().map { entry ->
                val wrapper = entry.jsonObject
                val element = wrapper["content"]?.jsonObject ?: return@map entry
                val marked =
                    withMarker(element, Marker(CONVERTER_VERSION, MarkerKind.BLOCK, hashOf(element)))
                JsonObject(wrapper + ("content" to marked))
            }
            val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
            val withEntries = JsonObject(
                obj + ("params" to JsonObject(params + ("content" to JsonArray(entries))))
            )
            withMarker(
                withEntries,
                Marker(CONVERTER_VERSION, MarkerKind.CHAPTER, sourceFiles.getOrElse(index) { "" })
            )
        }
        return JsonObject(book + ("chapters" to JsonArray(chapters)))
    }

    /** The marker of one chapter or element, or `null` if it carries none. */
    fun markerOf(element: JsonObject): Marker? {
        val comments = (element["metadata"]?.jsonObject?.get(FIELD) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: return null
        return parse(comments.lineSequence().firstOrNull().orEmpty())
    }

    /**
     * Reads a marker off its first line. Split on `:` with a limit, so that the last field - which
     * is free text, a file name - keeps any `:` of its own.
     */
    fun parse(line: String): Marker? {
        val parts = line.trim().split(':', limit = 4)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val kind = MarkerKind.of(parts[2]) ?: return null
        return Marker(version = parts[1], kind = kind, key = parts[3])
    }

    fun format(marker: Marker): String =
        "$PREFIX:${marker.version}:${marker.kind.token}:${marker.key}\n$NOTE"

    /**
     * A fingerprint of **what an element says**, which has to mean the same thing across two
     * conversions *and* survive a platform writing the package back out untouched.
     *
     * Three fields are stripped at every depth - a row carries all of them on its columns too - and
     * each for its own reason:
     *
     * - `subContentId`, because it is freshly random per conversion
     * - `authorComments`, because it is where the hash itself is written
     * - `metadata` entirely, because Moodle writes license = 'U' here when opened
     *
     * and a `library` field keeps its machine name without its version, for the same reason one level
     * further out - see [libraryName].
     *
     * Every string that is left is [normalizeText]ed first, so what the hash covers is the *words* of
     * a block rather than the bytes they arrived in.
     */
    fun hashOf(element: JsonElement): String {
        val canonical = Json.encodeToString(JsonElement.serializer(), canonicalize(element))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private val ignored = setOf("subContentId", FIELD, "metadata")

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.keys
                .filter { it !in ignored }
                .sorted()
                .associateWith { key ->
                    val value = element.getValue(key)
                    if (key == "library") libraryName(value) else canonicalize(value)
                }
        )

        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(normalizeText(element.content)) else element
    }

    /**
     * A `library` field down to its machine name: `"H5P.Table 1.2"` becomes `"H5P.Table"`.
     */
    private fun libraryName(value: JsonElement): JsonElement =
        (value as? JsonPrimitive)?.takeIf { it.isString }
            ?.let { JsonPrimitive(it.content.substringBefore(' ')) }
            ?: canonicalize(value)

    private val TAG = Regex("<[^>]*>")

    private val WHITESPACE = Regex("\\s+")

    /**
     * One string as a reader would hear it: no markup, no entities, no whitespace at all.
     */
    private fun normalizeText(text: String): String =
        text.replace(TAG, "")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(WHITESPACE, "")

    private fun withMarker(element: JsonObject, marker: Marker): JsonObject {
        val metadata = element["metadata"]?.jsonObject ?: JsonObject(emptyMap())
        return JsonObject(
            element + ("metadata" to JsonObject(metadata + (FIELD to JsonPrimitive(format(marker)))))
        )
    }
}

/** The `{ content, useSeparator }` wrappers of one chapter; the wrapper travels with its element. */
internal fun JsonObject.contentEntries(): List<JsonElement> =
    this["params"]?.jsonObject?.get("content")?.jsonArray.orEmpty()

/** The element inside such a wrapper. */
internal fun JsonElement.element(): JsonObject =
    jsonObject["content"]?.jsonObject ?: JsonObject(emptyMap())

/** The title an element or chapter shows, which is what a chapter falls back to when renamed. */
internal fun JsonObject.metadataTitle(): String =
    (this["metadata"]?.jsonObject?.get("title") as? JsonPrimitive)?.content.orEmpty()
