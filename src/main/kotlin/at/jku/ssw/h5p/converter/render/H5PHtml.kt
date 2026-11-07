package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning

/**
 * What H5P actually lets through, and the markup we are therefore allowed to emit.
 *
 * Both Lumi and Moodle run every text field of the content through a sanitizer built from the library's
 * `semantics.json`: Moodle uses `H5PContentValidator::validateText` (h5p-php-library), Lumi uses
 * `SemanticsEnforcer.enforceText` (h5p-server). Moodle is the target - and on tags, styles and
 * file types it is also the stricter of the two, so what is recorded here holds for both. Attributes
 * are the exception: Lumi drops `id`, `class` and `title`, but only when it *saves*.
 *
 * A disallowed tag is not an error - both sanitizers drop the tag and keep its text - but the
 * markup is silently lost, so it is better to render something that survives in the first place.
 */
object H5PHtml {

    /** Added by both sanitizers regardless of what the library asks for. */
    private val ALWAYS_ALLOWED = setOf("div", "span", "p", "br")

    /**
     * H5P.AdvancedText 1.1. Notably absent: `blockquote`, `sup`, `sub`, `u`, `ins`, `input`, `img`,
     * `table`, `h1` and `h4`-`h6`.
     */
    val ADVANCED_TEXT_TAGS: Set<String> = ALWAYS_ALLOWED + setOf(
        "strong", "em", "del", "a", "ul", "ol", "h2", "h3", "hr", "pre", "code",
        // derived by both sanitizers from the tags above
        "li", "s"
    )

    /** H5P.Table 1.2. No `img`, and none of the block-level tags AdvancedText has. */
    val TABLE_TAGS: Set<String> = ALWAYS_ALLOWED + setOf(
        "strong", "em", "del", "a", "table", "code", "s",
        // added by both sanitizers because `table` is present
        "tr", "td", "th", "colgroup", "col", "thead", "tbody", "tfoot", "figure", "figcaption"
    )

    // On attributes the two disagree: Moodle keeps everything except `style`, `on*` and `-*`, while
    // Lumi keeps only a short list per tag (`style` everywhere, `href` and `target` on `a`, `colspan`
    // on cells, ...). So `id`, `class` and `title` survive in Moodle, and in Lumi until it saves.

    /**
     * Every anchor has to name its own frame, because H5P *writes* the document the content lives
     * in and the first thing in that document's head is `<base target="_parent">`
     * (`H5P.getHeadTags` in `h5p.js`; `H5P.InteractiveBook` declares `"embedTypes": ["iframe"]`, so
     * there is always such a frame). A link with no `target` of its own is therefore followed in the
     * *surrounding* frame - which has no `#ch3-setup` in it, so the click does nothing whatsoever.
     * `_self` puts the link back into the document that holds the anchor.
     */
    const val IN_PAGE_LINK_TARGET = " target=\"_self\""

    /**
     * The same base tag, the other direction: left alone, a link to another site replaces the frame
     * the book is running in with that site - inside Moodle a box a few hundred pixels tall, with no
     * way back. A new tab is what an author writing `[H5P](https://h5p.org)` means.
     */
    const val EXTERNAL_LINK_TARGET = " target=\"_blank\""

    /**
     * Extensions allowed inside the package. Moodle uses `H5PCore::$defaultContentWhitelist`
     * unchanged; Lumi's list is the same plus `svg` and `xml`, but Moodle's is the binding one:
     */
    val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff")

    /**
     * Background shades for nested callouts. `background-color` is one of the few styles that
     * survive in AdvancedText (its semantics set `font.background`), and it is the only way to box
     * something off - `border` and `padding` are unlocked for table libraries only.
     */
    val CALLOUT_SHADES = listOf("#f2f2f2", "#e6e6e6", "#dadada", "#cecece", "#c2c2c2", "#b6b6b6", "#aaaaaa", "#9e9e9e", "#929292", "#868686")

    /** Muted text for footnote bodies - `color` survives because AdvancedText sets `font.color`. */
    const val MUTED_COLOR = "color:#666666"

    const val INS_COLOR = "#d8f5d8"

    /**
     * The box a code block sits in. `pre` is not on Moodle's `$allowed_styleable_tags`, so it cannot
     * be shaded itself - the fill goes on a `div` around it.
     *
     * Deliberately not one of the [CALLOUT_SHADES]: those only ever get darker with nesting depth, and
     * a code block inside a blockquote still has to be tellable apart from the grey it sits on.
     */
    const val CODE_BLOCK_STYLE = "background-color:#f6f8fa"

    /** Tables carry their own borders: nothing in the H5P stylesheets draws them. */
    const val TABLE_STYLE = "border-collapse:collapse"
    const val CELL_STYLE = "border:1px solid #cccccc;padding:0.5em"
    const val HEADER_CELL_STYLE = "$CELL_STYLE;background-color:#f2f2f2"

    /**
     * The pseudo-table's cells are not boxed, since having a border on the `H5P.RowColumn`s looks bad
     * when there is text + image + text in the same field (then you have three boxes in a field)
     */
    const val ROW_CELL_STYLE = "padding:0.5em"
    const val ROW_HEADER_CELL_STYLE = "$ROW_CELL_STYLE;background-color:#f2f2f2"

    /** Tags that never have a closing partner, so they must not be pushed on the balance stack. */
    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    private val TAG_PATTERN = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)\s*>""")

    /**
     * Warns about raw HTML the author wrote in the Markdown. It is passed through untouched either
     * way, but the parts that will not survive are worth saying
     * out loud, and unbalanced markup renders differently on the two platforms: Lumi re-serializes
     * and repairs it, Moodle's regex-based filter leaves it broken and it can bleed into the page.
     */
    fun checkRawHtml(html: String, allowedTags: Set<String> = ADVANCED_TEXT_TAGS): List<Warning> {
        val warnings = mutableListOf<Warning>()
        val disallowed = linkedSetOf<String>()
        val openTags = mutableListOf<String>()
        val unclosed = linkedSetOf<String>()

        for (match in TAG_PATTERN.findAll(html)) {
            val (closing, rawName, _, selfClosing) = match.destructured
            val name = rawName.lowercase()

            if (name !in allowedTags) {
                disallowed.add(name)
            }

            if (name in VOID_TAGS || selfClosing.isNotEmpty()) {
                continue
            }

            if (closing.isEmpty()) {
                openTags.add(name)
            } else {
                // Close the most recent matching tag; anything opened after it was never closed.
                val index = openTags.lastIndexOf(name)
                if (index == -1) {
                    unclosed.add("</$name>")
                } else {
                    for (i in openTags.size - 1 downTo index + 1) {
                        unclosed.add("<${openTags[i]}>")
                    }
                    openTags.subList(index, openTags.size).clear()
                }
            }
        }

        unclosed.addAll(openTags.map { "<$it>" })

        if (disallowed.isNotEmpty()) {
            val tags = disallowed.joinToString(", ") { "<$it>" }
            val hint = if ("table" in disallowed) {
                " Write tables as Markdown tables - those become a real H5P table."
            } else {
                ""
            }
            warnings.add(
                Warning(
                    "Raw HTML uses $tags, which H5P does not support in a text block. " +
                        "The tag is removed when the book is displayed, its text content is kept.$hint"
                )
            )
        }

        if (unclosed.isNotEmpty()) {
            warnings.add(
                Warning(
                    "Raw HTML is unbalanced (${unclosed.joinToString(", ")} never matched up). " +
                        "It is passed through as written, but it may affect the rest of the page."
                )
            )
        }

        return warnings
    }
}
