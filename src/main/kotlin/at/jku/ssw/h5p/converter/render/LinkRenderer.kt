package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.BookIndex
import at.jku.ssw.h5p.converter.ChapterRef
import at.jku.ssw.h5p.converter.WikiLink
import org.commonmark.ext.heading.anchor.IdGenerator
import org.commonmark.node.Link
import java.io.File
import java.net.URLDecoder

/**
 * Links, which are the one construct whose right answer depends on the *book* rather than on the
 * node - a `[text](other.md)` has to know which chapter `other.md` became.
 *
 * Within one chapter a link is an ordinary anchor and works. Across chapters it cannot: Interactive
 * Book navigates only through a hash fragment, and it refuses one that does not carry the id the
 * platform assigned the book at upload:
 *
 * ```js
 * validateFragments = t => void 0 !== t.chapter && String(t.h5pbookid) === String(contentId)
 * ```
 *
 * That id is a database autoincrement handed out when the uploaded file is first viewed, and it
 * changes every time the file is replaced - so no converter can know it. A link to another chapter
 * therefore becomes a reference the reader can follow by hand.
 */
class LinkRenderer(
    private val html: HtmlWriter,
    private val warnings: MutableSet<Warning>,
    private val index: BookIndex,
    private val current: ChapterRef
) {

    fun render(link: Link, renderText: () -> Unit) {
        val destination = link.destination.orEmpty()

        // Absolute URLs and autolinks
        if (destination.isEmpty() || SCHEME.containsMatchIn(destination) || destination.startsWith("//")) {
            writeLink(destination, link.title, renderText)
            return
        }

        val fragment = destination.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val path = destination.substringBefore('#')

        if (path.isEmpty()) {
            // A plain "#anchor": the same chapter, and the anchor lives in this chapter's namespace.
            writeLink("#${anchorIn(current, link, fragment.orEmpty())}", link.title, renderText)
            return
        }

        val chapter = resolveChapter(link, path)
        if (chapter != null) {
            writeChapterLink(chapter, link, fragment, renderText)
            return
        }

        // A .md file that is not part of this book cannot be linked to at all: there is nothing in
        // the package it could point at, and the raw path would send the player's iframe off the book.
        if (link is WikiLink || path.endsWith(".md", ignoreCase = true)) {
            warnings.add(
                Warning(
                    "\"${current.file.name}\" links to \"$path\", which is not a chapter of this book. " +
                        "The link text is kept, the link itself is dropped."
                )
            )
            renderText()
            return
        }

        writeLink(destination, link.title, renderText)
    }

    /** A wiki link names a note, a Markdown link names a path - try the matching one first. */
    private fun resolveChapter(link: Link, path: String): ChapterRef? {
        // `URLDecoder` implements the form-encoding scheme, in which `+` means a space. A link
        // destination is a path, where it means `+` - so a chapter called `C++.md` would be looked
        // up as `C  .md` and reported as "not a chapter of this book". Escaping the plus first
        // leaves percent-encoding (`My%20Page.md`) decoded and the plus itself alone.
        val decoded = runCatching {
            URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)
        }.getOrDefault(path)

        if (link is WikiLink) {
            return index.byName(decoded) ?: index.byFile(File(current.file.parentFile, decoded))
        }

        if (!decoded.endsWith(".md", ignoreCase = true)) return null

        return index.byFile(File(current.file.parentFile, decoded))
    }

    /**
     * The id a fragment refers to inside [target]. A Markdown link carries the generated slug
     * already (`#installing-lumi`); a wiki link carries the heading *text* (`#Installing Lumi`), so
     * it goes through the very generator that produced the id. A fresh one each time - a shared one
     * would start appending `-1` to a repeated anchor.
     */
    private fun anchorIn(target: ChapterRef, link: Link, fragment: String): String {
        if (link !is WikiLink) return "${target.anchorPrefix}$fragment"
        return IdGenerator.builder().prefix(target.anchorPrefix).build().generateId(fragment)
    }

    private fun writeChapterLink(target: ChapterRef, link: Link, fragment: String?, renderText: () -> Unit) {
        val title = link.title

        if (target.number == current.number) {
            // Same chapter, same document: an ordinary anchor works. Without a fragment the chapter's
            // own `div` is the target - it is the one chapter that is not `display: none`.
            val anchor = if (fragment != null) anchorIn(target, link, fragment) else target.chapterElementId
            writeLink("#$anchor", title, renderText)
            return
        }

        // Another chapter: not linkable, so say where it is instead of leaving a link that does
        // nothing. The chapter number and title are enough to find it in the sidebar.
        warnings.add(
            Warning(
                "\"${current.file.name}\" links to \"${target.file.name}\". Due to technical limitations, " +
                    "links to other files cannot be rendered as actual links, so the link is " +
                    "rendered as a reference to chapter ${target.number} instead."
            )
        )
        renderText()
        html.buffer.append(
            "<span style=\"${H5PHtml.MUTED_COLOR}\"> (→ chapter ${target.number}, " +
                "“${escapeHtml(target.title)}”)</span>"
        )
    }

    /**
     * The `target` is not decoration - it is what makes the link work at all. H5P's own `<base
     * target="_parent">` sends every untargeted link to the frame *around* the book, so an in-page
     * anchor has to ask for `_self` and a link off the site for `_blank`. See [H5PHtml] and D22.
     */
    private fun writeLink(href: String, title: String?, renderText: () -> Unit) {
        val titleAttr = if (!title.isNullOrEmpty()) " title=\"${escapeHtml(title)}\"" else ""
        val target = if (href.startsWith("#")) H5PHtml.IN_PAGE_LINK_TARGET else H5PHtml.EXTERNAL_LINK_TARGET
        html.pushTag("a", "href=\"${escapeHtml(href)}\"$target$titleAttr")
        renderText()
        html.popTag()
    }

    private companion object {
        /** `http:`, `mailto:`, `C:` - anything the author meant to leave this package for. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    }
}
