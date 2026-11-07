package at.jku.ssw.h5p.converter

import org.commonmark.node.CustomNode
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Position
import org.commonmark.parser.beta.Scanner

/**
 * A formula, captured verbatim so that nothing between the delimiters is parsed as Markdown.
 *
 * Only the inline form is translated. `H5P.MathDisplay` recognizes `\(...\)`, `\[...\]` and
 * `$$...$$`, and *not* a single `$...$`; current versions of the addon use a fixed MathJax setup
 * that cannot be reconfigured. So `$$...$$` is already a spelling both sides understand and is left
 * exactly as the author wrote it, while `$...$` has to become `\(...\)` to render at all.
 */
class MathInline(val literal: String, val display: Boolean) : CustomNode() {

    fun delimited(): String = if (display) "\$\$$literal\$\$" else "\\($literal\\)"
}

/**
 * Recognizes `$...$` and `$$...$$`, and deliberately **not** `\(...\)` or `\[...\]`.
 *
 * Both dollar forms need parsing even though only one of them is rewritten: without it `_` or `*`
 * inside a formula turns into emphasis, whatever the delimiters are. The backslash forms are left
 * alone on purpose: no Markdown editor renders them as math, so an author who writes `\(` means a
 * literal parenthesis and their preview shows one. Leaving them to commonmark's
 * `BackslashInlineParser` produces exactly that, `(`, which is also what keeps `H5P.MathDisplay`
 * from finding a formula where the author intended none.
 */
class MathInlineParserFactory : InlineContentParserFactory {

    override fun getTriggerCharacters(): Set<Char> = setOf('$')

    override fun create(): InlineContentParser = MathInlineParser()
}

private class MathInlineParser : InlineContentParser {

    override fun tryParse(inlineParserState: InlineParserState): ParsedInline? {
        val scanner = inlineParserState.scanner()
        if (scanner.peek() != '$') return ParsedInline.none()
        return parseDollar(scanner)
    }

    private fun parseDollar(scanner: Scanner): ParsedInline? {
        if (scanner.next("$$")) {
            // Display math is written on its own and is often spread over several lines, so the only
            // requirement is that it closes.
            val literal = readUntil(scanner, scanner.position(), "$$") ?: return ParsedInline.none()
            return ParsedInline.of(MathInline(literal, display = true), scanner.position())
        }

        scanner.next()
        val begin = scanner.position()

        // A single `$` is far too common in prose and shell snippets to take at face value, so the
        // pandoc rule applies: no whitespace just inside either delimiter, and no digit right after
        // the closing one. A candidate that fails it is skipped rather than fatal - "$5 and $10$"
        // still finds its formula, while "costs $5 and $10 more" finds none and stays text.
        while (true) {
            val literal = readUntil(scanner, begin, "$") ?: return ParsedInline.none()
            if (!literal.first().isWhitespace() && !literal.last().isWhitespace() && !scanner.peek().isDigit()) {
                return ParsedInline.of(MathInline(literal, display = false), scanner.position())
            }
        }
    }

    /**
     * Everything from [begin] up to the next [closing], with the scanner left after it. Returns null
     * if the delimiter never closes, or if it closes immediately - `$$` on its own is not a formula.
     */
    private fun readUntil(scanner: Scanner, begin: Position, closing: String): String? {
        while (scanner.hasNext()) {
            val beforeClosing = scanner.position()

            if (scanner.next(closing)) {
                return scanner.getSource(begin, beforeClosing).content.ifEmpty { null }
            }

            // `\$`, `\\` and every other LaTeX escape: two characters, neither of which can close.
            if (scanner.peek() == '\\') {
                scanner.next()
            }
            scanner.next()
        }
        return null
    }
}
