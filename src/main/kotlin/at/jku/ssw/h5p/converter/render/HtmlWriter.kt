package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.converter.PageBuilder
import at.jku.ssw.h5p.converter.json.H5PElement

/**
 * A content block on its way into the page, together with what to say about it where it lands.
 *
 * Only [element] ever reaches the package; the other two exist for contexts that cannot simply
 * insert a block and have to explain themselves instead.
 */
data class PlacedBlock(
    val element: H5PElement,
    /** Plain text to leave behind where the block would have gone, for a context that can only mark the spot. */
    val placeholder: String = "",
    /** Names the source in warnings - for an image, the path as it was written in the Markdown. */
    val source: String = ""
)

/**
 * Where the renderer is currently writing, and - the part that matters - what that place does when
 * something arrives that cannot be expressed as H5P markup.
 *
 * H5P allows `img` in no text field at all, so an image is always a content block of its own, and
 * every construct that can enclose one needs an answer to "then where does it go?".
 */
interface RenderContext {
    val buffer: StringBuilder

    fun place(block: PlacedBlock)
}

/**
 * An open HTML element, remembered so it can be closed again - or dropped if it stayed empty.
 *
 * It keeps the exact string it was last opened with rather than rebuilding it, because one element
 * does *not* reopen identically: an interrupted `ol` has to come back carrying a `start`, or the
 * items after the interruption begin counting at one again (see [HtmlWriter.reopenTags]).
 */
class HtmlTag(val name: String, attributes: String = "", val startNumber: Int = 1) {

    /** What was written the last time this tag was opened. */
    var opened: String = openingTag(name, attributes)
        private set

    /** How many `li` have been opened inside this element. Only an `ol` ever asks. */
    var itemsOpened: Int = 0

    fun reopenedAs(attributes: String): String =
        openingTag(name, attributes).also { opened = it }

    fun close() = "</$name>"
}

private fun openingTag(name: String, attributes: String) =
    "<$name${if (attributes.isNotEmpty()) " $attributes" else ""}>"

/**
 * The markup plumbing every renderer writes through: one buffer per context, a stack of open tags,
 * and the mechanics of interrupting the text for a content block.
 */
class HtmlWriter(private val page: PageBuilder) {

    private val pageContext = PageContext()

    /** Innermost first is the top; the page context is at the bottom and is never popped. */
    private val contexts = mutableListOf<RenderContext>(pageContext)

    private val tagStack = mutableListOf<HtmlTag>()

    /** Where markup goes right now. */
    val buffer: StringBuilder
        get() = contexts.last().buffer

    /** Hands [block] to whatever encloses the current position - see [RenderContext]. */
    fun place(block: PlacedBlock) = contexts.last().place(block)

    /** Renders [body] into [context] instead of the enclosing one. */
    fun <T> withContext(context: RenderContext, body: () -> T): T {
        contexts.add(context)
        try {
            return body()
        } finally {
            contexts.removeAt(contexts.size - 1)
        }
    }

    fun pushTag(name: String, attributes: String = "") = push(HtmlTag(name, attributes))

    /**
     * An ordered list, which is the one element that has to know where it began: [startNumber] is
     * what the author wrote (`5.` starts at five), and it is also what a list interrupted by a
     * content block has to resume from.
     */
    fun pushOrderedList(startNumber: Int) =
        push(HtmlTag("ol", if (startNumber == 1) "" else "start=\"$startNumber\"", startNumber))

    fun popTag() {
        if (tagStack.isEmpty()) return
        val tag = tagStack.removeLast()
        // A `li` reopened after an interruption and then left empty means the item ended with the
        // block. Dropping it is right, but the list it was reopened into stays, and the next item
        // becomes its first - so the `start` it was given is now one too low.
        if (closeTag(tag) && tag.name == "li") {
            resumeListOneItemLater()
        }
    }

    private fun push(tag: HtmlTag) {
        if (tag.name == "li") {
            enclosingList()?.takeIf { it.name == "ol" }?.let { it.itemsOpened++ }
        }
        tagStack.add(tag)
        buffer.append(tag.opened)
    }

    private fun enclosingList(): HtmlTag? = tagStack.lastOrNull { it.name == "ol" || it.name == "ul" }

    /**
     * Closes every open tag so a content block can be inserted, and returns them so [reopenTags] can
     * pick the text up again afterwards. The tag stack itself is left alone - the elements are still
     * open as far as the document is concerned, they are only interrupted.
     */
    fun closeOpenTags(): List<HtmlTag> {
        val open = tagStack.toList()
        for (tag in open.reversed()) {
            closeTag(tag)
        }
        return open
    }

    /**
     * Picks the text up again after an interruption. An `ol` is reopened as a *new* list element, so
     * it has to be told to carry on counting - the item that was open when the block arrived is the
     * one that continues.
     */
    fun reopenTags(tags: List<HtmlTag>) {
        for (tag in tags) {
            buffer.append(if (tag.name == "ol") tag.reopenedAs(resumedFrom(tag)) else tag.opened)
        }
    }

    private fun resumedFrom(list: HtmlTag, itemsAlreadyDone: Int = list.itemsOpened - 1) =
        "start=\"${list.startNumber + itemsAlreadyDone.coerceAtLeast(0)}\""

    /** See [popTag]: rewrites the `start` of the reopened list the dropped `li` was sitting in. */
    private fun resumeListOneItemLater() {
        val list = enclosingList()?.takeIf { it.name == "ol" } ?: return
        if (!buffer.endsWith(list.opened)) return
        buffer.setLength(buffer.length - list.opened.length)
        buffer.append(list.reopenedAs(resumedFrom(list, list.itemsOpened)))
    }

    /** Commits the page's flowing text as an `H5P.AdvancedText` block, if there is anything to show. */
    fun flushText() {
        val content = pageContext.buffer.toString()
        if (content.isEmpty()) return

        pageContext.buffer.clear()
        if (hasVisibleText(content)) {
            page.addText(content)
        }
    }

    /**
     * Closes [tag], or drops it again if nothing was written inside it, and reports which it was.
     * Empty elements are what an interruption leaves behind: the open tags are closed before the
     * content block and reopened after it, so without this an image between two paragraphs is
     * followed by a stray `<p></p>`.
     */
    private fun closeTag(tag: HtmlTag): Boolean {
        // Trailing whitespace does not count as content: a list item left holding only the newline
        // its paragraph wrote is still an empty item, and would render as a bullet with nothing
        // next to it.
        var end = buffer.length
        while (end > 0 && buffer[end - 1].isWhitespace()) end--

        if (end >= tag.opened.length && buffer.substring(end - tag.opened.length, end) == tag.opened) {
            buffer.setLength(end - tag.opened.length)
            return true
        }
        buffer.append(tag.close())
        return false
    }

    private inner class PageContext : RenderContext {
        override val buffer = StringBuilder()

        override fun place(block: PlacedBlock) {
            val open = closeOpenTags()
            flushText()
            page.addContent(block.element)
            reopenTags(open)
        }
    }
}

private val HTML_TAG = Regex("<[^>]*>")

/** Whether [html] shows anything at all, or is only markup - an empty block is not worth committing. */
fun hasVisibleText(html: String): Boolean = html.replace(HTML_TAG, "").trim().isNotEmpty()

fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
