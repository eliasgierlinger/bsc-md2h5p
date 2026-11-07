package at.jku.ssw.h5p.merge

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.ConversionResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val CONTENT_ENTRY = "content/content.json"
private const val H5P_ENTRY = "h5p.json"

private val json = Json { prettyPrint = false; encodeDefaults = true }

/**
 * Folds a package that was edited in H5P back together with a fresh conversion of the Markdown.
 *
 * The policy is one sentence: **the Markdown supplies every block it generates, the
 * edited package supplies only what the Markdown cannot, and alignment decides where the latter
 * goes.** An edit made in the editor to generated text is therefore overwritten by the converted
 * version and warned about; what has to be carried across is the quiz, the video, whatever only
 * exists in the package, and the real question is *where it goes* once the Markdown around it has
 * moved.
 *
 * Each package answers that about itself through the markers it carries in its own `authorComments`
 * fields: an addition remembers the block it followed, and it is put back after whatever that
 * block became - recognized by content rather than by an exact hash, so that fixing a typo in the
 * paragraph above a quiz does not lose the quiz's place.
 */
fun mergePackages(newPackage: File, editedPackage: File): ConversionResult {
    val warnings = mutableListOf<Warning>()
    val staging = File(WorkDirs.createTempDir("md2h5p-merge-"), newPackage.name)

    H5PArchive(newPackage).use { fresh ->
        H5PArchive(editedPackage).use { edited ->
            val freshBook = fresh.jsonAt(CONTENT_ENTRY)
            val editedBook = edited.jsonAt(CONTENT_ENTRY)

            val editedChapters = editedBook.array("chapters").map { it.jsonObject }
            if (editedChapters.none { Markers.markerOf(it) != null }) {
                throw IllegalArgumentException(
                    "The uploaded package carries no md2h5p markers, so there is no way to tell which " +
                        "of its blocks came from Markdown and which were added by hand. Only a package " +
                        "that this tool produced can be updated."
                )
            }

            val foreignVersions = editedChapters.mapNotNull { Markers.markerOf(it)?.version }
                .filter { it != Markers.CONVERTER_VERSION }
                .distinct()
            if (foreignVersions.isNotEmpty()) {
                warnings.add(
                    Warning(
                        "The uploaded package was built by converter version " +
                            "${foreignVersions.joinToString(", ")}, this one is " +
                            "${Markers.CONVERTER_VERSION}. It is merged normally, but its blocks " +
                            "record their content in an older form, so this round cannot tell you " +
                            "which of them were edited in H5P. The next merge can."
                    )
                )
            }

            val freshInfo = fresh.jsonAt(H5P_ENTRY)
            val editedInfo = edited.jsonAt(H5P_ENTRY)
            val freshVersions = Libraries.versionsOf(fresh, freshInfo)
            val editedVersions = Libraries.versionsOf(edited, editedInfo)
            reportUpgrades(freshVersions, editedVersions, warnings)
            val merged = mergeBook(freshBook, editedBook, warnings)
            val chosen = Libraries.resolve(
                fresh = freshVersions,
                edited = editedVersions,
                referenced = Libraries.referenced(merged),
                archives = listOf(edited, fresh),
                warnings = warnings
            )
            val mergedBook = Libraries.retarget(merged, chosen).jsonObject
            val mergedInfo = mergeInfo(freshInfo, editedInfo, chosen)

            staging.parentFile.mkdirs()
            H5PArchiveWriter(staging.outputStream()).use { out ->
                out.add(CONTENT_ENTRY, json.encodeToString(JsonElement.serializer(), mergedBook))
                out.add(H5P_ENTRY, json.encodeToString(JsonElement.serializer(), mergedInfo))

                // Files the merged content actually points at, taken from whichever package has them
                for (path in referencedFiles(mergedBook).sorted()) {
                    val entry = "content/$path"
                    val bytes = fresh.bytes(entry) ?: edited.bytes(entry)
                    if (bytes != null) out.add(entry, bytes)
                }

                // Everything outside content/ is the libraries. The edited package's copy of one wins,
                // but only the *chosen* version's folder is copied: shipping both leaves a package
                // whose content names one of them and whose h5p.json declares two. A chosen version
                // neither package carries is declared and not shipped
                val folders = chosen.map { (name, version) -> Libraries.folderOf(name, version) }.toSet()
                for (name in edited.names + fresh.names) {
                    if (name.startsWith("content/") || name == H5P_ENTRY) continue
                    val folder = name.substringBefore('/')
                    if (folder != name && folder !in folders) continue
                    val bytes = edited.bytes(name) ?: fresh.bytes(name)
                    if (bytes != null) out.add(name, bytes)
                }
            }
        }
    }

    staging.copyTo(newPackage, overwrite = true)
    staging.parentFile.deleteRecursively()
    return ConversionResult(newPackage, warnings)
}

private fun H5PArchive.jsonAt(entry: String): JsonObject =
    Json.parseToJsonElement(text(entry) ?: error("package has no $entry")).jsonObject

private fun mergeBook(
    freshBook: JsonObject,
    editedBook: JsonObject,
    warnings: MutableList<Warning>
): JsonObject {
    val freshChapters = freshBook.array("chapters").map { it.jsonObject }
    val editedChapters = editedBook.array("chapters").map { it.jsonObject }

    // Split the edited book into the chapters that came from Markdown and the chapters that
    // somebody added in the editor, each remembering which derived chapter it followed.
    val derived = mutableListOf<JsonObject>()
    val addedChapters = mutableListOf<Addition>()
    var pending: Addition? = null
    for (chapter in editedChapters) {
        if (Markers.markerOf(chapter) == null) {
            val addition = pending ?: Addition(derived.size - 1).also {
                pending = it
                addedChapters.add(it)
            }
            addition.items.add(chapter)
        } else {
            pending = null
            derived.add(chapter)
        }
    }

    // Which fresh chapter each old one became: same source file, else same title, else the same text
    // (see chapterScore). A renamed .md must not read as "deleted, and a different one added", which
    // would orphan everything that had been added inside it.
    val alignment = align(derived, freshChapters, ::chapterScore)

    val merged = freshChapters.toMutableList()
    val rumpChapters = mutableListOf<JsonObject>()
    derived.forEachIndexed { k, chapter ->
        val target = alignment[k]
        if (target < 0) {
            // The Markdown file is gone. Its text goes with it, but nothing hand-made is thrown away:
            // the additions are kept in a chapter of their own at the end of the book.
            val marker = Markers.markerOf(chapter)
            rumpChapter(chapter, marker?.key.orEmpty(), chapter.metadataTitle(), warnings)
                ?.let { rumpChapters.add(it) }
        } else {
            merged[target] = mergeChapter(fresh = merged[target], edited = chapter, warnings = warnings)
        }
    }

    // A chapter is never split the way a block can be, so there is nothing to walk forward over -
    // but the end of a book is an anchor of its own in the same way the end of a chapter is, so a
    // chapter added after the last one from the Markdown stays last however many the Markdown adds.
    val anchors = IntArray(alignment.size) {
        when {
            alignment[it] < 0 -> -1
            it == alignment.size - 1 -> merged.size
            else -> alignment[it] + 1
        }
    }
    val withAdded = splice(merged, addedChapters, alignment, anchors) { addition ->
        warnings.add(
            Warning(
                "${addition.items.size} chapter(s) added in H5P could not be put back in the same " +
                    "place, because the chapter they followed is no longer in the Markdown. They were " +
                    "moved to the nearest position that still exists."
            )
        )
    }

    return JsonObject(freshBook + ("chapters" to JsonArray(withAdded + rumpChapters)))
}

/** One chapter that exists on both sides: the fresh text, with the editor's additions put back. */
private fun mergeChapter(
    fresh: JsonObject,
    edited: JsonObject,
    warnings: MutableList<Warning>
): JsonObject {
    val title = edited.metadataTitle()
    val freshEntries = fresh.contentEntries()
    val freshBlocks = freshEntries.map { it.element() }

    val derived = mutableListOf<JsonObject>()
    val additions = mutableListOf<Addition>()
    var pending: Addition? = null

    for (entry in edited.contentEntries()) {
        val element = entry.element()
        val marker = Markers.markerOf(element)?.takeIf { it.kind == MarkerKind.BLOCK }
        val recorded = marker?.key

        // A hash recorded by another converter version was taken over different fields, so it can
        // disagree with this one for no reason at all. The block is still Markdown-derived and is
        // still aligned; it just cannot be reported on.
        val comparable = marker != null && marker.version == Markers.CONVERTER_VERSION

        if (recorded == null) {
            val addition = pending ?: Addition(derived.size - 1).also {
                pending = it
                additions.add(it)
            }
            addition.items.add(entry)
            continue
        }
        pending = null
        derived.add(element)

        if (comparable && Markers.hashOf(element) != recorded) {
            warnings.add(
                Warning(
                    "A text block of chapter \"$title\" looks like it was edited in H5P. The Markdown " +
                        "is the source of that text, so the converted version was used instead and the H5P edit is lost."
                )
            )
        }
    }

    val alignment = align(derived, freshBlocks, ::blockScore)

    if (additions.isNotEmpty() && looksReordered(derived, freshBlocks, alignment)) {
        warnings.add(
            Warning(
                "Blocks of chapter \"$title\" appear to have changed places in the Markdown. The " +
                    "alignment cannot follow a reordering, so something added in H5P may now sit " +
                    "under a different paragraph than it did before. Check that chapter."
            )
        )
    }

    val anchors = anchorEnds(alignment, derived, freshBlocks)
    val withAdditions = splice(freshEntries, additions, alignment, anchors) { addition ->
        warnings.add(
            Warning(
                "${addition.items.size} block(s) added in H5P to chapter \"$title\" could not be put " +
                    "back in the same place, because the block they followed is no longer in the " +
                    "Markdown. They were placed as close to it as possible."
            )
        )
    }

    val params = fresh["params"]?.jsonObject ?: JsonObject(emptyMap())
    return JsonObject(fresh + ("params" to JsonObject(params + ("content" to JsonArray(withAdditions)))))
}

/** What is left of a chapter whose Markdown file is gone: only what a person put there by hand. */
private fun rumpChapter(
    chapter: JsonObject,
    sourceFile: String,
    chapterTitle: String,
    warnings: MutableList<Warning>
): JsonObject? {
    val kept = chapter.contentEntries().filter { Markers.markerOf(it.element()) == null }
    if (kept.isEmpty()) return null

    warnings.add(
        Warning(
            "The Markdown file \"$sourceFile\" no longer exists, so chapter \"$chapterTitle\" was not " +
                "rebuilt. The ${kept.size} block(s) that had been added to it in H5P were kept in a " +
                "chapter at the end of the book."
        )
    )

    val title = "$chapterTitle (kept from the previous version)"
    val metadata = chapter["metadata"]?.jsonObject ?: JsonObject(emptyMap())
    val params = chapter["params"]?.jsonObject ?: JsonObject(emptyMap())
    return JsonObject(
        chapter +
            ("params" to JsonObject(params + ("content" to JsonArray(kept)))) +
            ("metadata" to JsonObject(
                metadata + ("title" to JsonPrimitive(title)) + ("extraTitle" to JsonPrimitive(title))
            ))
    )
}

// --- recognizing a block again ------------------------------------------------------------------

/**
 * Which fresh item each old one became, or -1 for one that is gone.
 *
 * Needleman-Wunsch, the standard sequence alignment: the best pairing of the two lists that never
 * crosses itself, maximizing the total score, with only pairs at or above [Similarity.MATCH]
 * allowed to pair at all. Not crossing is what makes two identical paragraphs align in order rather
 * than both onto the same one.
 */
private fun align(
    old: List<JsonObject>,
    fresh: List<JsonObject>,
    score: (JsonObject, JsonObject) -> Double
): IntArray {
    val alignment = IntArray(old.size) { -1 }
    if (old.isEmpty() || fresh.isEmpty()) return alignment

    val scores = Array(old.size) { i -> DoubleArray(fresh.size) { j -> score(old[i], fresh[j]) } }

    // best[i][j]: the best total over the first i old and first j fresh items.
    val best = Array(old.size + 1) { DoubleArray(fresh.size + 1) }
    for (i in 1..old.size) {
        for (j in 1..fresh.size) {
            val pair = scores[i - 1][j - 1]
            val paired = if (pair >= Similarity.MATCH) best[i - 1][j - 1] + pair else Double.NEGATIVE_INFINITY
            best[i][j] = maxOf(paired, best[i - 1][j], best[i][j - 1])
        }
    }

    var i = old.size
    var j = fresh.size
    while (i > 0 && j > 0) {
        val pair = scores[i - 1][j - 1]
        if (pair >= Similarity.MATCH && best[i][j] == best[i - 1][j - 1] + pair) {
            alignment[i - 1] = j - 1
            i--
            j--
        } else if (best[i][j] == best[i - 1][j]) {
            i--
        } else {
            j--
        }
    }
    return alignment
}

/**
 * Where an addition that followed old item `k` belongs: the index to insert *before*, or -1 when the
 * item it followed is gone.
 *
 * Normally that is one past what the item became, with two exceptions.
 *
 * An addition that followed the **last** item from the Markdown was at the end of the chapter, and
 * that is where it stays, whatever the Markdown appends after that item. This is the mirror of an
 * addition that came before the first block staying first, and it exists for the same reason: the
 * end of a chapter is a position in its own right, not a fact about the block above it.
 *
 * The walk forward is for the remaining case, an addition in the middle of a chapter whose block the
 * Markdown **split**: putting an image or a table into the middle of a run of paragraphs
 * makes one `H5P.AdvancedText` become `text / image / text`, so the old block corresponds to a run
 * of new ones and an addition that followed it belongs after the *last* piece, not in the middle of
 * the paragraph it was written under. A fresh block only counts as another piece if nothing else
 * claimed it, and it still reads like the old one ([Similarity.SPLIT]).
 *
 * The opposite - deleting an image, so three blocks become one - needs nothing: several old blocks
 * then align onto the same region and the addition lands after it either way.
 */
private fun anchorEnds(
    alignment: IntArray,
    old: List<JsonObject>,
    fresh: List<JsonObject>
): IntArray {
    val claimed = BooleanArray(fresh.size)
    alignment.forEach { if (it >= 0) claimed[it] = true }

    return IntArray(alignment.size) { k ->
        val start = alignment[k]
        if (start < 0) return@IntArray -1
        if (k == alignment.size - 1) return@IntArray fresh.size

        // Walk over blocks nothing else claimed, remembering the furthest one that still reads like
        // the old block. The blocks in between need not: what split the paragraph run is precisely a
        // picture or a table, which resembles nothing. The first block another old one claimed ends
        // the walk - past that we are in somebody else's territory.
        var last = start
        var scan = start
        while (scan + 1 < fresh.size && !claimed[scan + 1]) {
            scan++
            if (blockScore(old[k], fresh[scan]) >= Similarity.SPLIT) last = scan
        }
        last + 1
    }
}

private fun looksReordered(
    old: List<JsonObject>,
    fresh: List<JsonObject>,
    alignment: IntArray
): Boolean = alignment.indices.any { k ->
    val target = alignment[k]
    if (target < 0) return@any false
    val paired = blockScore(old[k], fresh[target])
    fresh.indices.any { j -> j != target && blockScore(old[k], fresh[j]) > paired }
}

// --- placing what only the package has ----------------------------------------------------------

/** A run of consecutive items that exist only in the edited package, and what they followed. */
private class Addition(val afterDerived: Int) {
    val items = mutableListOf<JsonElement>()
}

/**
 * Puts each run of additions back into [freshItems], at the place [anchors] gives for the item it
 * followed.
 *
 * When that item is gone from the Markdown - deleted outright, not merely edited, since an edit is
 * still recognized - the search walks back to the nearest earlier item that did survive, then
 * forward to the nearest later one (placing the addition before that instead). If *nothing* survived
 * it keeps the only thing it still knows: an addition that came after the last item goes to the end
 * and one that came before the first goes to the top, for the same reason the start of a chapter is
 * an anchor that cannot go missing - neither can the end. Everything but an exact hit reports through
 * [onFallback]: it is a guess, and a guess should say so.
 */
private fun splice(
    freshItems: List<JsonElement>,
    additions: List<Addition>,
    alignment: IntArray,
    anchors: IntArray,
    onFallback: (Addition) -> Unit
): List<JsonElement> {
    if (additions.isEmpty()) return freshItems

    val insertBefore = sortedMapOf<Int, MutableList<JsonElement>>()
    for (addition in additions) {
        var index: Int? = null
        var exact = true

        if (addition.afterDerived < 0) {
            // It came before everything, and the start of a chapter is an anchor that cannot go
            // missing. Searching forward for the first surviving block would push it *past* an
            // opening paragraph that happens to have been rewritten.
            index = 0
        } else {
            for (k in addition.afterDerived downTo 0) {
                if (anchors[k] >= 0) {
                    index = anchors[k]
                    exact = k == addition.afterDerived
                    break
                }
                exact = false
            }
            if (index == null) {
                for (k in addition.afterDerived + 1 until alignment.size) {
                    if (alignment[k] >= 0) {
                        index = alignment[k]
                        break
                    }
                }
                exact = false
            }
            if (index == null) {
                index = if (addition.afterDerived >= alignment.size - 1) freshItems.size else 0
            }
        }

        if (!exact) onFallback(addition)
        insertBefore.getOrPut(index) { mutableListOf() }.addAll(addition.items)
    }

    val merged = mutableListOf<JsonElement>()
    for (i in 0..freshItems.size) {
        insertBefore[i]?.let { merged.addAll(it) }
        if (i < freshItems.size) merged.add(freshItems[i])
    }
    return merged
}

// --- h5p.json and files -------------------------------------------------------------------------

/**
 * The fresh metadata, with the dependency list of both packages. E.g. an
 * `H5P.MultiChoice` added in the editor is carried over as content, and without its library declared
 * here it would be content nothing can render.
 */
private fun mergeInfo(fresh: JsonObject, edited: JsonObject, chosen: Map<String, String>): JsonObject {
    val dependencies = LinkedHashMap<String, JsonElement>()
    for (d in fresh.array("preloadedDependencies") + edited.array("preloadedDependencies")) {
        val name = d.jsonObject.string("machineName")
        val version = chosen[name] ?: continue
        dependencies.putIfAbsent(name, dependency(name, version))
    }
    return JsonObject(fresh + ("preloadedDependencies" to JsonArray(dependencies.values.toList())))
}

private fun dependency(machineName: String, version: String): JsonObject = JsonObject(
    mapOf(
        "machineName" to JsonPrimitive(machineName),
        "majorVersion" to JsonPrimitive(version.substringBefore('.').toInt()),
        "minorVersion" to JsonPrimitive(version.substringAfter('.').toInt())
    )
)

/**
 * Says which libraries the merged book is no longer built on the version this converter pins.
 */
private fun reportUpgrades(
    fresh: Map<String, String>,
    edited: Map<String, String>,
    warnings: MutableList<Warning>
) {
    val changed = fresh.filter { (name, version) -> name in edited && edited[name] != version }
    if (changed.isEmpty()) return
    warnings.add(
        Warning(
            "The uploaded package was saved by an H5P that ships other versions of " +
                changed.entries.joinToString(", ") { (name, version) -> "$name $version -> ${edited[name]}" } +
                ". The merged book is built on the uploaded package's versions, since that is what " +
                "the blocks added there need. Content upgrade scripts that these libraries may " +
                "define are not run."
        )
    )
}

/**
 * Every file the content points at. H5P states a file the same way whatever the library
 * (`{ "path": ..., "mime": ... }`), so this finds the ones belonging to libraries we know nothing
 * about too - which is the whole point of not throwing them away.
 */
private fun referencedFiles(element: JsonElement): Set<String> = buildSet {
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val path = (node["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (path != null && !path.contains("://")) add(path)
                node.values.forEach { walk(it) }
            }

            is JsonArray -> node.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(element)
}

// --- small json helpers -------------------------------------------------------------------------

private fun JsonObject.array(name: String): List<JsonElement> = this[name]?.jsonArray ?: emptyList()

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()
