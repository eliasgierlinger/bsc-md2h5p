package at.jku.ssw.h5p.converter

import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.beta.LinkInfo
import org.commonmark.parser.beta.LinkProcessor
import org.commonmark.parser.beta.LinkResult
import org.commonmark.parser.beta.Scanner

/**
 * `[[Target]]`, `[[Target|label]]` and `[[Target#Section]]` - the way Obsidian and most wikis link
 * between notes. Commonmark does not know the syntax and leaves it as literal text.
 *
 * This is a [LinkProcessor] rather than a custom inline parser because `InlineParserImpl.parseInline`
 * handles `case '['` in a hard-coded switch *before* it consults any parser registered for a trigger
 * character - a parser on `[` would never be called. The footnotes extension hooks `[^label]` the
 * same way.
 */
class WikiLinkProcessor : LinkProcessor {

    override fun process(linkInfo: LinkInfo, scanner: Scanner, context: InlineParserContext): LinkResult? {
        // In `[[Target]]` the inner bracket closes first and arrives as a shortcut link: no
        // destination, no label, and the scanner sitting on the outer `]`.
        if (linkInfo.destination() != null || linkInfo.label() != null) return LinkResult.none()

        val beforeBracket = linkInfo.openingBracket()?.previous as? Text ?: return LinkResult.none()
        if (!beforeBracket.literal.endsWith("[")) return LinkResult.none()

        val (target, label) = split(linkInfo.text())
        if (target.isEmpty()) return LinkResult.none()

        // Last check, because it moves the scanner past the outer `]`.
        if (!scanner.next(']')) return LinkResult.none()

        // `![[x]]` is the embed form. Its `!` belongs to the *outer* bracket, so it is not reported
        // as linkInfo.marker() - `parseLinkMarker` leaves it as its own text node right before the
        // bracket's.
        val bang = (beforeBracket.previous as? Text)?.takeIf { beforeBracket.literal == "[" && it.literal == "!" }

        // Both were emitted as text and the outer bracket is never closed - we just consumed its
        // `]` - so without this they would survive as a stray `![` in front of the link.
        beforeBracket.literal = beforeBracket.literal.dropLast(1)
        bang?.literal = ""

        val node = if (bang != null) Image(target, null) else WikiLink(target)
        node.appendChild(Text(label))

        return LinkResult.replaceWith(node, scanner.position())
    }

    /**
     * `Target|label` -> the two halves. Without a `|` the target is its own label, minus the
     * `#Section` part - nobody wants to read "Setup#Installing Lumi" in running text.
     */
    private fun split(text: String): Pair<String, String> {
        val pipe = text.indexOf('|')
        if (pipe >= 0) return text.substring(0, pipe).trim() to text.substring(pipe + 1).trim()

        val target = text.trim()
        return target to target.substringBefore('#').trim().ifEmpty { target }
    }
}

/**
 * A link that was written as `[[Target]]`. It is a [Link] and is visited as one; the marker exists
 * because a wiki link names a *note*, not a path - it resolves against the chapter names of the book
 * before it is tried as a relative path.
 */
class WikiLink(destination: String) : Link(destination, null)
