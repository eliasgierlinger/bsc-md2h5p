package at.jku.ssw.h5p.merge

import at.jku.ssw.h5p.Warning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which version of each library the merged package ships.
 *
 * A package names its libraries in three places at once, and they have to agree: the folder each
 * library lives in (`H5P.Column-1.18/`), the `preloadedDependencies` of `h5p.json`, and the `library`
 * field of every element in `content.json`. Merging two packages is the one moment where the three
 * can drift apart, because the package that comes back from the editor is not the one that went in:
 * a platform upgrades what it has installed, so a book converted against `H5P.Column 1.18` comes back
 * as `H5P.Column 1.22`, with `H5P.InteractiveBook 1.15` and whatever the author added built on
 * versions to match.
 *
 * The edited package decides, and its Column's semantics decide for the blocks. The
 * edited package is the one that has been through a real platform, and the block the merge exists to
 * carry over was built against *its* libraries. But it need not carry every library it uses - H5P
 * accepts a package that only declares a library the platform has installed - so for a library it
 * does not carry there is a better authority than either package's folder list: the `semantics.json`
 * of the Column that contains every block, which names the exact version of each content type it
 * accepts. [resolve] reads it, and `content.json` is rewritten to say what it found - one version per
 * library, never two, and the folder shipped where either package has it.
 */
internal object Libraries {

    private val FOLDER = Regex("^(.+)-(\\d+)\\.(\\d+)$")

    private val LIBRARY_STRING = Regex("^([\\w.]+) (\\d+\\.\\d+)$")

    /**
     * `machineName` to `"major.minor"` for every library the package uses.
     *
     * A package normally carries the libraries it
     * declares, but it does not have to: H5P accepts one whose `h5p.json` names a library the platform
     * already has installed, and an editor prunes the folders of everything the book stopped using. So
     * a library that is only declared is okay also.
     */
    fun versionsOf(archive: H5PArchive, info: JsonObject): Map<String, String> = buildMap {
        for (dependency in info["preloadedDependencies"] as? JsonArray ?: JsonArray(emptyList())) {
            val d = dependency as? JsonObject ?: continue
            val name = (d["machineName"] as? JsonPrimitive)?.content ?: continue
            val major = (d["majorVersion"] as? JsonPrimitive)?.content ?: continue
            val minor = (d["minorVersion"] as? JsonPrimitive)?.content ?: continue
            put(name, "$major.$minor")
        }
        for (name in archive.names) {
            val top = name.substringBefore('/')
            if (top == name || top == "content") continue
            val match = FOLDER.matchEntire(top) ?: continue
            put(match.groupValues[1], "${match.groupValues[2]}.${match.groupValues[3]}")
        }
    }

    /** The folder a chosen library lives in, which is the only one of its folders that is copied. */
    fun folderOf(machineName: String, version: String) = "$machineName-$version"

    /** Splits `"H5P.Column 1.18"`; `null` for anything that is not a library reference. */
    fun parse(library: String): Pair<String, String>? =
        LIBRARY_STRING.matchEntire(library.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    /**
     * Every `library` field of the tree set to the version [chosen] ships, at any depth.
     */
    fun retarget(element: JsonElement, chosen: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                val named = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { parse(it) }
                if (key == "library" && named != null) {
                    JsonPrimitive("${named.first} ${chosen[named.first] ?: named.second}")
                } else {
                    retarget(value, chosen)
                }
            }
        )

        is JsonArray -> JsonArray(element.map { retarget(it, chosen) })
        else -> element
    }

    /** The library whose semantics name the version of every block in a chapter. */
    private const val CONTAINER = "H5P.Column"

    /**
     * The version of every library the merged package will name.
     *
     * Three sources, in order. The container's `semantics.json` first: an `H5P.Column` names the
     * exact version of every content type it accepts. Then the
     * edited package's own versions, which are what its platform runs. The template's version is the
     * last resort, for a library the edited package neither carries nor declares.
     */
    fun resolve(
        fresh: Map<String, String>,
        edited: Map<String, String>,
        referenced: Set<String>,
        archives: List<H5PArchive>,
        warnings: MutableList<Warning>
    ): Map<String, String> {
        val chosen = LinkedHashMap(fresh).apply { putAll(edited) }
        val container = chosen[CONTAINER] ?: return chosen
        for (option in acceptedBy(CONTAINER, container, archives)) {
            val (name, version) = parse(option) ?: continue
            if (name !in referenced || chosen[name] == version) continue
            warnings.add(
                Warning(
                    "\"$CONTAINER $container\" accepts \"$name $version\", so the book's " +
                        "\"$name ${chosen[name]}\" blocks are declared as $version - H5P drops a block " +
                        "built on any other version than the one its container names."
                )
            )
            chosen[name] = version
        }
        return chosen
    }

    /**
     * The `Name major.minor` entries of one library's `semantics.json`, wherever they sit in it, or
     * nothing if no archive carries that library at that version - the edited package is asked first,
     * so the semantics read are the ones its platform validated the book against.
     */
    private fun acceptedBy(machineName: String, version: String, archives: List<H5PArchive>): Set<String> {
        val entry = "${folderOf(machineName, version)}/semantics.json"
        val text = archives.firstNotNullOfOrNull { it.bytes(entry) }?.toString(Charsets.UTF_8)
            ?: return emptySet()
        return buildSet { collectOptions(Json.parseToJsonElement(text), this) }
    }

    private fun collectOptions(node: JsonElement, out: MutableSet<String>) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                if (key == "options" && value is JsonArray) {
                    for (option in value) {
                        val text = (option as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (text != null && parse(text) != null) out.add(text)
                    }
                } else {
                    collectOptions(value, out)
                }
            }

            is JsonArray -> node.forEach { collectOptions(it, out) }
            else -> Unit
        }
    }

    /** Every distinct `library` value in a content tree, by machine name. */
    fun referenced(node: JsonElement): Set<String> = buildSet {
        fun walk(current: JsonElement) {
            when (current) {
                is JsonObject -> {
                    val library = (current["library"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    parse(library.orEmpty())?.let { add(it.first) }
                    current.values.forEach { walk(it) }
                }

                is JsonArray -> current.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(node)
    }
}
