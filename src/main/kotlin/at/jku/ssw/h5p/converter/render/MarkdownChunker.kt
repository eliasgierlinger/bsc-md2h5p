package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.BookIndex
import at.jku.ssw.h5p.converter.ChapterRef
import at.jku.ssw.h5p.converter.ImageResolver
import at.jku.ssw.h5p.converter.MathInline
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.heading.anchor.IdGenerator
import org.commonmark.ext.image.attributes.ImageAttributes
import org.commonmark.ext.ins.Ins
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.*
import kotlin.math.min

/**
 * Renders one chapter's Markdown into H5P content blocks.
 */
class MarkdownChunker(
    images: ImageResolver,
    index: BookIndex,
    chapter: ChapterRef
) : AbstractVisitor() {

    private val _warnings = mutableSetOf<Warning>()

    val warnings: List<Warning>
        get() = _warnings.toList()

    private val html = HtmlWriter(chapter.page)
    private val imageRenderer = ImageRenderer(images, _warnings)
    private val tableRenderer = TableRenderer(html, _warnings, ::visitChildren)
    private val footnoteRenderer = FootnoteRenderer(html, chapter, ::visitChildren)
    private val linkRenderer = LinkRenderer(html, _warnings, index, chapter)
    private val codeHighlighter = CodeHighlighter(_warnings)
    private val customRenderer = CustomRenderer(_warnings)

    // Every id this chapter generates is namespaced: Interactive Book keeps several chapters in one
    // document, so ids that restart per chapter collide.
    private val idGenerator = IdGenerator.builder().prefix(chapter.anchorPrefix).build()

    /** Nesting depth of blockquotes/alerts, so nested callouts can be shaded apart. */
    private var calloutDepth = 0

    /** A task list marker waiting for its item's paragraph to open. */
    private var pendingTaskMarker: String? = null

    /**
     * Raw HTML the author wrote, collected across the whole chapter so that tags opened in one
     * `HtmlInline` node and closed in another are still seen as balanced.
     */
    private val rawHtmlFragments = StringBuilder()

    /** Commits whatever text is still buffered. Called once more by the caller when the file ends. */
    fun flushText() = html.flushText()

    override fun visit(document: Document) {
        footnoteRenderer.collect(document)
        visitChildren(document)
        footnoteRenderer.renderSection()
        _warnings.addAll(H5PHtml.checkRawHtml(rawHtmlFragments.toString()))
    }

    override fun visit(customBlock: CustomBlock) {
        when (customBlock) {
            is TableBlock -> tableRenderer.render(customBlock)

            is Alert -> {
                // The alerts extension only produces an Alert node for the five standard types, so
                // an unknown [!CUSTOM] never gets here - it stays a plain BlockQuote and is rendered as one.
                val label = AlertsExtension.STANDARD_TYPES[customBlock.type]
                val emoji = ALERT_EMOJI[customBlock.type.uppercase()]
                if (label == null || emoji == null) {
                    renderCallout(QUOTE_EMOJI) { visitChildren(customBlock) }
                } else {
                    renderCallout("$emoji $label") { visitChildren(customBlock) }
                }
            }

            is FootnoteDefinition -> {
                // Rendered separately at the end of the chapter, not at its position in the source.
            }

            is YamlFrontMatterBlock -> {
                // Metadata only, not renderable content.
            }

            else -> visitChildren(customBlock)
        }
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is TableHead, is TableBody, is TableRow, is TableCell -> tableRenderer.renderStructure(customNode)

            is FootnoteReference -> footnoteRenderer.renderReference(customNode)

            is Strikethrough -> {
                html.pushTag("del")
                visitChildren(customNode)
                html.popTag()
            }

            is Ins -> {
                // `ins` is not allowed, and neither is `u` - but `background-color` on a `span` is,
                // and a shade nothing else uses keeps inserted text distinct from ordinary emphasis
                // without leaking the `++` markers into the book.
                html.pushTag("span", "style=\"background-color:${H5PHtml.INS_COLOR}\"")
                visitChildren(customNode)
                html.popTag()
            }

            is TaskListItemMarker -> {
                // `input` is not allowed, so the box is a character. The marker sits next to the
                // item's paragraph rather than inside it, so it is held back until that paragraph
                // opens - otherwise the box ends up on a line of its own above the item text.
                pendingTaskMarker = if (customNode.isChecked) "☑ " else "☐ "
            }

            is MathInline -> {
                // Plain text on purpose: MathJax reads the delimiters back out of the rendered page,
                // so the formula only has to survive the sanitizer, not be marked up. Escaping is
                // still needed - `\(a < b\)` becomes `&lt;` in the HTML and `<` again in the DOM.
                html.buffer.append(escapeHtml(customNode.delimited()))
            }

            is ImageAttributes -> {
                // Consumed directly by ImageRenderer; not rendered as standalone content.
            }

            else -> visitChildren(customNode)
        }
    }

    override fun visit(image: Image) {
        // An image is always a content block of its own - where it goes is up to whatever encloses it.
        imageRenderer.blockFor(image)?.let { html.place(it) }
    }

    override fun visit(blockQuote: BlockQuote) {
        renderCallout(QUOTE_EMOJI) { visitChildren(blockQuote) }
    }

    /**
     * A blockquote or alert as a shaded box. `blockquote` itself is not allowed, and neither is any
     * kind of border or padding outside tables, so a background fill is the only thing left that
     * sets a block apart visually. Unlike a `pre` box it can hold lists, code and nested quotes.
     */
    private fun renderCallout(label: String, body: () -> Unit) {
        val shade = H5PHtml.CALLOUT_SHADES[min(calloutDepth, H5PHtml.CALLOUT_SHADES.size - 1)]
        calloutDepth++
        try {
            html.pushTag("div", "style=\"background-color:$shade\"")
            html.buffer.append("\n<p><strong>$label</strong></p>\n")
            body()
            html.popTag()
            html.buffer.append("\n")
        } finally {
            calloutDepth--
        }
    }

    override fun visit(paragraph: Paragraph) {
        html.pushTag("p")
        pendingTaskMarker?.let {
            html.buffer.append(it)
            pendingTaskMarker = null
        }
        visitChildren(paragraph)
        html.popTag()
        html.buffer.append("\n")
    }

    override fun visit(heading: Heading) {
        // h2 and h3 are the only headings AdvancedText allows: h1 is out, and h4-h6 are stripped
        // down to plain text, which loses the line breaks around them too. So everything shifts down
        // one level and then clamps - an h4 still reads as a heading, just not a smaller one.
        val adjustedLevel = min(heading.level + 1, 3)
        val id = idGenerator.generateId(plainTextOf(heading))
        html.pushTag("h$adjustedLevel", "id=\"${escapeHtml(id)}\"")
        visitChildren(heading)
        html.popTag()
        html.buffer.append("\n")
    }

    override fun visit(bulletList: BulletList) {
        html.pushTag("ul")
        html.buffer.append("\n")
        visitChildren(bulletList)
        html.popTag()
        html.buffer.append("\n")
    }

    override fun visit(orderedList: OrderedList) {
        // The start number is not decoration: a list interrupted by an image or a table is closed
        // and reopened, and the list that resumes has to be told where to carry on.
        html.pushOrderedList(orderedList.markerStartNumber ?: 1)
        html.buffer.append("\n")
        visitChildren(orderedList)
        html.popTag()
        html.buffer.append("\n")
    }

    override fun visit(listItem: ListItem) {
        html.pushTag("li")
        visitChildren(listItem)
        html.popTag()
        html.buffer.append("\n")
    }

    override fun visit(thematicBreak: ThematicBreak) {
        html.buffer.append("<hr />\n")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        // A fence whose info string names a custom construct is not code at all - it is a quiz, and
        // a quiz is a content block, so it goes where an image goes rather than into the text. The
        // renderer is asked first and falls back to code, which is also what it returns when the
        // block is malformed: the reader then sees the source the author wrote, plus a warning.
        if (customRenderer.handles(fencedCodeBlock.info)) {
            customRenderer.blockFor(fencedCodeBlock)?.let {
                html.place(it)
                return
            }
            // Fall back with no info string: the label named a construct, not a language, so
            // "language singlechoice is not known to the syntax highlighter" would be noise on top
            // of the warning that already said what was actually wrong with the block.
            html.buffer.append(codeHighlighter.render(fencedCodeBlock.literal, info = null))
            return
        }
        html.buffer.append(codeHighlighter.render(fencedCodeBlock.literal, fencedCodeBlock.info))
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        // An indented block has no way to name a language, so it gets the box but no color.
        html.buffer.append(codeHighlighter.render(indentedCodeBlock.literal, info = null))
    }

    override fun visit(htmlBlock: HtmlBlock) {
        rawHtmlFragments.append(htmlBlock.literal).append("\n")
        html.buffer.append(htmlBlock.literal)
        html.buffer.append("\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        rawHtmlFragments.append(htmlInline.literal)
        html.buffer.append(htmlInline.literal)
    }

    override fun visit(text: Text) {
        html.buffer.append(escapeHtml(text.literal))
    }

    override fun visit(emphasis: Emphasis) {
        html.pushTag("em")
        visitChildren(emphasis)
        html.popTag()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        html.pushTag("strong")
        visitChildren(strongEmphasis)
        html.popTag()
    }

    override fun visit(code: Code) {
        html.pushTag("code")
        html.buffer.append(escapeHtml(code.literal))
        html.popTag()
    }

    override fun visit(link: Link) {
        linkRenderer.render(link) { visitChildren(link) }
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        html.buffer.append("<br />\n")
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        html.buffer.append("\n")
    }

    override fun visit(linkReferenceDefinition: LinkReferenceDefinition) {
        visitChildren(linkReferenceDefinition)
    }

    private companion object {
        /** Label for a plain blockquote, which has no type of its own to name. */
        const val QUOTE_EMOJI = "💬"

        /** One per GFM alert type, keyed the way the alerts extension reports them. */
        val ALERT_EMOJI = mapOf(
            "NOTE" to "ℹ️",
            "TIP" to "💡",
            "IMPORTANT" to "❗",
            "WARNING" to "⚠️",
            "CAUTION" to "🛑"
        )
    }
}
