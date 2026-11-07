package at.jku.ssw.h5p.converter

import java.io.File

/**
 * What every chapter needs to know about all the others.
 *
 * A chapter is rendered on its own, but a link that leaves the file has to name its target: which
 * chapter it is, what it is called, and - within the current chapter - the id of its `div` in the
 * player. That id is `h5p-interactive-book-chapter-<subContentId>`, and the `subContentId` is ours:
 * it is written into `content.json` and Moodle's `validateLibrary` keeps a well-formed one, so the
 * value we generate is the value the player uses at runtime.
 *
 * The index is therefore built in a first pass over all files, before any of them is rendered.
 */
class BookIndex(val chapters: List<ChapterRef>) {

    private val byCanonicalPath: Map<String, ChapterRef> =
        chapters.associateBy { it.canonicalPath }

    /**
     * By the names a wiki link may use, because `[[Setup]]` names a note rather than a path - and
     * the note an author has in mind is not called `2_Setup.md`. Built back to front so that on a
     * duplicate the earlier chapter wins (`put` overwrites).
     */
    private val byName: Map<String, ChapterRef> = buildMap {
        for (chapter in chapters.reversed()) {
            for (name in chapter.names) put(name, chapter)
        }
    }

    fun byFile(file: File): ChapterRef? =
        byCanonicalPath[runCatching { file.canonicalPath }.getOrNull() ?: return null]

    /** [name] may be written as `Setup`, `Setup.md` or `notes/Setup` - only the last segment counts. */
    fun byName(name: String): ChapterRef? {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        return byName[leaf.lowercase().removeSuffix(".md")]
    }
}

/**
 * One chapter, as seen from the outside. [number] is 1-based and drives both the anchor namespace
 * and the way a chapter is referred to in text when it cannot be linked.
 */
class ChapterRef(
    val number: Int,
    val file: File,
    val title: String,
    val page: PageBuilder
) {

    val canonicalPath: String = runCatching { file.canonicalPath }.getOrDefault(file.path)

    /**
     * Namespace for every id this chapter generates. Interactive Book keeps several chapters in one
     * document, so ids that restart per chapter collide.
     */
    val anchorPrefix: String = "ch$number-"

    /** The id Interactive Book gives this chapter's `div`, and the value its `chapter=` fragment takes. */
    val chapterElementId: String = "h5p-interactive-book-chapter-${page.subContentId}"

    /**
     * What a wiki link may call this chapter: the file name, the file name without the ordering
     * prefix an author would not type, and the title.
     */
    val names: Set<String> = setOf(
        file.nameWithoutExtension,
        file.nameWithoutExtension.replaceFirst(NUMBER_PREFIX, ""),
        title
    ).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
