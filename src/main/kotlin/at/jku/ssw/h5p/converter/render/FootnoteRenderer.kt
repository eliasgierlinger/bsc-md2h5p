package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.converter.ChapterRef
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.node.Node

/**
 * Footnotes, which are defined anywhere and rendered at the end.
 *
 * A definition may sit before or after the reference that uses it, the numbering is by *first
 * reference* rather than by definition order, and a footnote body may itself reference a footnote
 * nobody has seen yet - so the definitions are collected up front and the section is rendered from a
 * worklist once the chapter is done.
 */
class FootnoteRenderer(
    private val html: HtmlWriter,
    private val chapter: ChapterRef,
    private val renderChildren: (Node) -> Unit
) {

    private val page = chapter.page

    /** Anchor ids are namespaced per chapter, because all chapters share one document (D19). */
    private fun anchorFor(label: String) = escapeHtml("${chapter.anchorPrefix}$label")

    /** Every definition in the chapter, in document order. */
    private val definitionsByLabel = mutableMapOf<String, FootnoteDefinition>()

    /** The labels actually referenced, in the order they were first referenced. */
    private val numbersByLabel = mutableMapOf<String, Int>()

    /** Walks the whole document before anything is rendered, because a definition may come last. */
    fun collect(node: Node) {
        var current = node.firstChild
        while (current != null) {
            if (current is FootnoteDefinition) {
                definitionsByLabel.putIfAbsent(current.label, current)
            }
            collect(current)
            current = current.next
        }
    }

    fun renderReference(reference: FootnoteReference) {
        val label = reference.label
        if (!definitionsByLabel.containsKey(label)) {
            html.buffer.append("[^${escapeHtml(label)}]")
            return
        }

        // `sup` is not allowed, so the reference is bracketed instead of raised. Only the first
        // reference carries the id - repeating it would be invalid HTML, and the back-link can only
        // ever point at one place anyway.
        val isFirstReference = !numbersByLabel.containsKey(label)
        val number = numbersByLabel.getOrPut(label) { numbersByLabel.size + 1 }
        val idAttr = if (isFirstReference) " id=\"fnref-${anchorFor(label)}\"" else ""
        html.buffer.append(
            "<a href=\"#fn-${anchorFor(label)}\"${H5PHtml.IN_PAGE_LINK_TARGET}$idAttr>[$number]</a>"
        )
    }

    /** Renders every referenced footnote as the closing section of the chapter. */
    fun renderSection() {
        if (numbersByLabel.isEmpty()) return

        html.flushText()
        val section = Section()

        var i = 0
        while (i < numbersByLabel.size) {
            // Re-reads the live key set each time so labels discovered while rendering earlier
            // footnotes (a footnote referencing another one) are still picked up. A normal iteration
            // would throw ConcurrentModificationException or silently drop them.
            val label = numbersByLabel.keys.elementAt(i)
            val definition = definitionsByLabel.getValue(label)
            section.openNumber = numbersByLabel.getValue(label)

            section.buffer.append("<li id=\"fn-${anchorFor(label)}\" style=\"${H5PHtml.MUTED_COLOR}\">")
            html.withContext(section) { renderChildren(definition) }
            section.buffer.append(
                " <a href=\"#fnref-${anchorFor(label)}\"${H5PHtml.IN_PAGE_LINK_TARGET}>↩</a>"
            )
            section.buffer.append("</li>")

            i++
        }

        section.buffer.append("</ol>")
        section.flush()
    }

    /**
     * The footnote list while it is being built. Unlike a table it can be torn apart: it is
     * page-level content, so a block that lands in it is simply put between two halves of the list.
     */
    private inner class Section : RenderContext {
        // A rule sets the notes apart from the chapter text; `hr` is one of the few block-level tags
        // AdvancedText allows.
        override val buffer = StringBuilder("<hr />\n<ol>")

        /** Number of the item currently open, so the second half can continue the numbering. */
        var openNumber = 0

        override fun place(block: PlacedBlock) {
            val open = html.closeOpenTags()
            buffer.append("</li></ol>")
            flush()

            page.addContent(block.element)

            // `start` keeps the numbering going, and is one of the few attributes both sanitizers
            // keep. The reopened `<li>` deliberately gets no `id` - the anchor the back-link points
            // at has to stay unique, and it belongs on the first half.
            buffer.append("<ol start=\"$openNumber\"><li style=\"${H5PHtml.MUTED_COLOR}\">")
            html.reopenTags(open)
        }

        fun flush() {
            val content = buffer.toString()
            buffer.clear()

            // Nothing but the list scaffolding is not worth a block of its own - but the rule is,
            // even when the first footnote opens with an image and leaves no text in front of it.
            if (hasVisibleText(content) || content.contains("<hr")) {
                page.addText(content, title = "Footnotes")
            }
        }
    }
}
