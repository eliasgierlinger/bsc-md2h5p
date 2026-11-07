package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.MAX_ROW_COLUMNS
import at.jku.ssw.h5p.converter.json.H5PElement
import at.jku.ssw.h5p.converter.rowElement
import at.jku.ssw.h5p.converter.tableElement
import at.jku.ssw.h5p.converter.textElement
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.CustomNode
import org.commonmark.node.Image
import org.commonmark.node.Node

/**
 * Tables, in the two shapes H5P leaves open.
 *
 * Normally a table is one `H5P.Table` block of HTML. That breaks down as soon as it contains an
 * image, because `img` is allowed in no text field: a picture in a cell can only be a content block,
 * and a content block cannot sit inside a block of HTML. So a table with an image is rebuilt out of
 * `H5P.Row`/`H5P.RowColumn`, where a cell *is* a container and can hold text, image, text. That only
 * works up to four columns - `H5P.Row`'s semantics cap its column list - so a wider table stays a
 * real table and the image is announced in the cell and committed right after it.
 */
class TableRenderer(
    private val html: HtmlWriter,
    private val warnings: MutableCollection<Warning>,
    private val renderChildren: (Node) -> Unit
) {

    fun render(table: TableBlock) {
        val rows = childrenRowsOf(table)
        val columnCount = rows.maxOfOrNull { childrenOfType<TableCell>(it).size } ?: 0

        if (containsType<Image>(table) && columnCount in 1..MAX_ROW_COLUMNS) {
            warnings.add(
                Warning(
                    "A table contains an image, which H5P cannot show inside a table. The table was " +
                        "rebuilt as a row layout so the image can be shown; it is no longer a single " +
                        "editable H5P table."
                )
            )
            renderAsRows(rows, columnCount)
        } else {
            renderAsTable(table)
        }
    }

    /** The `thead`/`tbody`/`tr`/`td` scaffolding, reached only while a real table is being built. */
    fun renderStructure(node: CustomNode) {
        when (node) {
            is TableHead -> wrap("thead", node)
            is TableBody -> wrap("tbody", node)
            is TableRow -> wrap("tr", node)
            is TableCell -> {
                val tag = if (node.isHeader) "th" else "td"
                html.buffer.append("<$tag style=\"${cellStyle(node.isHeader, node.alignment)}\">")
                renderChildren(node)
                html.buffer.append("</$tag>")
            }
        }
    }

    private fun wrap(tag: String, node: Node) {
        html.buffer.append("<$tag>")
        renderChildren(node)
        html.buffer.append("</$tag>")
    }

    private fun renderAsTable(table: TableBlock) {
        val context = TableHtml()
        html.withContext(context) { renderChildren(table) }
        context.buffer.append("</table>")

        html.place(PlacedBlock(tableElement(context.buffer.toString())))

        // Whatever could not go into a cell follows the table, in the order it appeared.
        context.displaced.forEach { html.place(it) }
    }

    private fun renderAsRows(rows: List<TableRow>, columnCount: Int) {
        for ((index, row) in rows.withIndex()) {
            val cells = childrenOfType<TableCell>(row)
            val columns = cells.map { renderCell(it) }.toMutableList()

            // A short row still has to occupy the full width, or the columns of the rows below it
            // would sit somewhere else.
            val isHeader = cells.firstOrNull()?.isHeader == true
            while (columns.size < columnCount) {
                columns.add(listOf(emptyCell(cellStyle(isHeader, null, bordered = false), isHeader)))
            }

            // The line between two rows is a block of its own between them, at page level and full
            // width, where no image can interrupt it. Asking the column for a separator instead
            // (`useSeparator: "enabled"`) draws a ruler up to H5P.Column 1.18, but from 1.22 on it
            // starts a new card that runs on into whatever follows the table.
            if (index > 0) {
                html.place(PlacedBlock(textElement("<hr>", title = "Row separator")))
            }
            html.place(PlacedBlock(rowElement(columns.take(columnCount), title = "Row")))
        }
    }

    private fun renderCell(cell: TableCell): List<H5PElement> {
        val context = Cell(cellStyle(cell.isHeader, cell.alignment, bordered = false), cell.isHeader)
        html.withContext(context) { renderChildren(cell) }
        context.flushText()

        // A column has to hold at least one thing, and an empty cell should still draw its box.
        if (context.contents.isEmpty()) {
            context.contents.add(emptyCell(context.style, context.isHeader))
        }
        return context.contents
    }

    private fun emptyCell(style: String, isHeader: Boolean) =
        tableElement(singleCellTable(style, isHeader, ""), "Cell")

    /**
     * One cell of the pseudo-table as its own tiny table. A table rather than a text block, because
     * `border` and `padding` are only allowed for libraries whose semantics list `table` - this is
     * what gives the pseudo-table its grid. Two neighbouring cells are two tables, though, so their
     * borders do not collapse into one line.
     */
    private fun singleCellTable(style: String, isHeader: Boolean, inner: String): String {
        val body = if (isHeader) {
            "<thead><tr><th style=\"$style\">$inner</th></tr></thead>"
        } else {
            "<tbody><tr><td style=\"$style\">$inner</td></tr></tbody>"
        }
        // The cell fills its column; `width` is one of the styles the table group unlocks.
        return "<table style=\"${H5PHtml.TABLE_STYLE};width:100%\">$body</table>"
    }

    /**
     * Alignment has to travel as a style: the `align` attribute is dropped on cells, and
     * borders/padding have to be spelled out per cell because nothing in H5P draws them and `tr`
     * cannot be styled at all.
     *
     * A real table draws its grid with [bordered] cells. The pseudo-table cannot - its cells are
     * separate elements that `H5P.RowColumn` insets and an image next to them is not, so a box would
     * stop where the picture starts. See [H5PHtml.ROW_CELL_STYLE].
     */
    private fun cellStyle(
        isHeader: Boolean,
        alignment: TableCell.Alignment?,
        bordered: Boolean = true
    ): String {
        val base = when {
            bordered && isHeader -> H5PHtml.HEADER_CELL_STYLE
            bordered -> H5PHtml.CELL_STYLE
            isHeader -> H5PHtml.ROW_HEADER_CELL_STYLE
            else -> H5PHtml.ROW_CELL_STYLE
        }
        return base + (alignment?.let { ";text-align:${it.name.lowercase()}" } ?: "")
    }

    /** Rows sit under a `TableHead`/`TableBody`, so they are one level deeper than the table. */
    private fun childrenRowsOf(table: TableBlock): List<TableRow> =
        childrenOfType<Node>(table).flatMap { childrenOfType<TableRow>(it) }

    /**
     * A real table, which is committed as one block of HTML and therefore cannot be interrupted.
     * A block that lands here is announced in the cell and held back until the table is done.
     */
    private inner class TableHtml : RenderContext {
        override val buffer = StringBuilder("<table style=\"${H5PHtml.TABLE_STYLE}\">")

        val displaced = mutableListOf<PlacedBlock>()

        override fun place(block: PlacedBlock) {
            buffer.append(escapeHtml(block.placeholder))
            displaced.add(block)

            // Only an image can get here: nothing else block-level can occur inside a Markdown table
            // cell, and a table with an image is rebuilt as rows unless it is too wide for that.
            warnings.add(
                Warning(
                    "Image \"${block.source}\" is inside a table cell, where H5P allows no images. " +
                        "The table has too many columns to be rebuilt as a row layout, so the " +
                        "image is shown as its own block directly after the table."
                )
            )
        }
    }

    /** A pseudo-table cell, which is a container - so a block that lands here is simply its next item. */
    private inner class Cell(val style: String, val isHeader: Boolean) : RenderContext {
        override val buffer = StringBuilder()

        val contents = mutableListOf<H5PElement>()

        override fun place(block: PlacedBlock) {
            val open = html.closeOpenTags()
            flushText()
            contents.add(block.element)
            html.reopenTags(open)
        }

        fun flushText() {
            val fragment = buffer.toString()
            buffer.clear()
            if (hasVisibleText(fragment)) {
                contents.add(tableElement(singleCellTable(style, isHeader, fragment), "Cell"))
            }
        }
    }
}
