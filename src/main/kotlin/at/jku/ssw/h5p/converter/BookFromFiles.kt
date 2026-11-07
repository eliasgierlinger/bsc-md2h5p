package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.json.H5PBook
import at.jku.ssw.h5p.converter.render.MarkdownChunker
import at.jku.ssw.h5p.converter.render.plainTextOf
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.image.attributes.ImageAttributesExtension
import org.commonmark.ext.ins.InsExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import java.io.File

/** [chapters] is in book order, so a caller can say which chapter came from which file (D26). */
data class BookBundle(
    val book: H5PBook,
    val warnings: List<Warning>,
    val chapters: List<ChapterRef> = emptyList()
)

/** A leading chapter number plus whatever separates it from the rest of the name: `03. `, `1_`, `2)`. */
internal val NUMBER_PREFIX = Regex("^(\\d+)[.)\\-_\\s]*")

private fun leadingNumber(fileName: String): Int? =
    NUMBER_PREFIX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

/** Whether a folder looks like an ordered book, which is what makes it the better chapter root. */
fun containsNumberedMdFile(mdFiles: List<File>): Boolean = mdFiles.any { leadingNumber(it.name) != null }

/** The fallback chapter title, derived from the file name once the ordering prefix is gone. */
private fun titleFromFileName(file: File): String =
    file.nameWithoutExtension
        .replaceFirst(NUMBER_PREFIX, "")
        .replace("_", " ")
        .trim()

/**
 * The chapter title the document itself asks for: a heading as the first thing in the file. YAML
 * front matter does not count as content, so a heading right after it still counts as first.
 */
private fun titleFromLeadingHeading(document: Node): String? {
    var node: Node? = document.firstChild
    while (node is YamlFrontMatterBlock) {
        node = node.next
    }
    return (node as? Heading)?.let { plainTextOf(it).trim().ifEmpty { null } }
}

fun createBookFromFiles(files: List<File>, images: ImageResolver): BookBundle {
    val warnings = mutableListOf<Warning>()

    val mdFiles = files.filter { it.extension.equals("md", ignoreCase = true) }

    // Numbered files come first in numeric order (so 10 sorts after 2, which a plain string sort
    // gets wrong), then everything else alphabetically. Unnumbered files used to be dropped
    // entirely as soon as one numbered file existed, which loses a chapter without saying so.
    val (numbered, unnumbered) = mdFiles.partition { leadingNumber(it.name) != null }
    val chapterFiles =
        numbered.sortedWith(compareBy({ leadingNumber(it.name) }, { it.name })) + unnumbered.sortedBy { it.name }

    if (numbered.isNotEmpty() && unnumbered.isNotEmpty()) {
        warnings.add(
            Warning(
                "Some Markdown files are numbered and some are not: " +
                    "${unnumbered.joinToString(", ") { it.name }} " +
                    "${if (unnumbered.size == 1) "has" else "have"} no number and " +
                    "${if (unnumbered.size == 1) "is" else "are"} placed after the numbered chapters."
            )
        )
    }

    val bookBuilder = BookBuilder().setShowCoverPage(false)

    val parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughExtension.create(),
                AlertsExtension.create(),
                FootnotesExtension.create(),
                ImageAttributesExtension.create(),
                InsExtension.create(),
                TaskListItemsExtension.create(),
                YamlFrontMatterExtension.create()
            )
        )
        // Neither `[[Target]]` nor `$x$` is commonmark, and neither is an extension: a wiki link
        // hooks in as a link processor and math as an inline content parser. The reasons are in
        // WikiLinkProcessor and MathInlineParser.
        .linkProcessor(WikiLinkProcessor())
        .customInlineContentParserFactory(MathInlineParserFactory())
        .build()

    // First pass: parse every file and name its chapter. A link may point forwards, so no chapter
    // can be rendered before every other one is known - including its subContentId, which is what a
    // link to it has to name.
    val documents = mutableListOf<Node>()
    val chapters = mutableListOf<ChapterRef>()

    for ((position, file) in chapterFiles.withIndex()) {
        val markdownContent = file.readText()

        if (markdownContent.isBlank()) {
            warnings.add(
                Warning(
                    "\"${file.name}\" is empty, so its chapter has no content. " +
                        "H5P skips such a chapter instead of showing an empty page."
                )
            )
        }

        val document = parser.parse(markdownContent)

        // A heading at the top of the file names the chapter better than its file name can. It stays
        // in the text as well - the title in the sidebar is often cut off, and the book should read
        // like the Markdown did.
        val title = titleFromLeadingHeading(document) ?: titleFromFileName(file)

        documents.add(document)
        chapters.add(ChapterRef(position + 1, file, title, PageBuilder(title)))
    }

    val index = BookIndex(chapters)

    // Second pass: render.
    for ((position, chapter) in chapters.withIndex()) {
        val chunker = MarkdownChunker(images, index, chapter)

        documents[position].accept(chunker)

        chunker.flushText()

        bookBuilder.addPage(chapter.page.build())
        warnings.addAll(chunker.warnings)
    }

    return BookBundle(bookBuilder.build(), warnings, chapters)
}