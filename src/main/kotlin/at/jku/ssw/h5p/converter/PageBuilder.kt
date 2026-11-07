package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Uuids
import at.jku.ssw.h5p.converter.json.H5PChapter
import at.jku.ssw.h5p.converter.json.H5PColumnContent
import at.jku.ssw.h5p.converter.json.H5PColumnParams
import at.jku.ssw.h5p.converter.json.H5PElement
import at.jku.ssw.h5p.converter.json.H5PImageCopyright
import at.jku.ssw.h5p.converter.json.H5PImageElement
import at.jku.ssw.h5p.converter.json.H5PImageFile
import at.jku.ssw.h5p.converter.json.H5PMetadata
import at.jku.ssw.h5p.converter.json.H5PMultiChoiceElement
import at.jku.ssw.h5p.converter.json.H5PSingleChoiceSetElement
import at.jku.ssw.h5p.converter.json.MultiChoiceAnswer
import at.jku.ssw.h5p.converter.json.MultiChoiceParams
import at.jku.ssw.h5p.converter.json.SingleChoice
import at.jku.ssw.h5p.converter.json.SingleChoiceSetParams
import at.jku.ssw.h5p.converter.json.H5PRowColumn
import at.jku.ssw.h5p.converter.json.H5PRowColumnElement
import at.jku.ssw.h5p.converter.json.H5PRowElement
import at.jku.ssw.h5p.converter.json.H5PSeparator
import at.jku.ssw.h5p.converter.json.H5PTableElement
import at.jku.ssw.h5p.converter.json.H5PTextElement
import at.jku.ssw.h5p.converter.json.ImageParams
import at.jku.ssw.h5p.converter.json.RowColumnParams
import at.jku.ssw.h5p.converter.json.RowParams
import at.jku.ssw.h5p.converter.json.TextParams
import kotlin.uuid.Uuid

class PageBuilder(private var title: String) {

    /**
     * Readable because it names the chapter in the player: its `div` gets the id
     * `h5p-interactive-book-chapter-<subContentId>`, which is what a link to this chapter targets.
     */
    val subContentId: Uuid = Uuids.next()

    private var extraTitle: String = title
    private val contents = mutableListOf<H5PColumnContent>()

    fun setTitle(title: String) = apply {
        this.title = title
    }

    fun setExtraTitle(extraTitle: String) = apply {
        this.extraTitle = extraTitle
    }

    fun addContent(element: H5PElement, separator: H5PSeparator = H5PSeparator.DISABLED) = apply {
        this.contents.add(
            H5PColumnContent(
                content = element,
                useSeparator = separator
            )
        )
    }

    fun addText(
        textHtml: String,
        title: String = "Untitled Text",
        separator: H5PSeparator = H5PSeparator.DISABLED
    ) = apply {
        addContent(textElement(textHtml, title), separator)
    }

    fun build(): H5PChapter {
        return H5PChapter(
            params = H5PColumnParams(content = this.contents.toList()),
            subContentId = this.subContentId,
            metadata = H5PMetadata(
                contentType = "Page",
                title = this.title,
                extraTitle = this.extraTitle
            )
        )
    }
}

// The elements are built outside the builder as well, because a row nests them: the caller has to
// be able to make an element without putting it on the page.

fun textElement(textHtml: String, title: String = "Untitled Text") = H5PTextElement(
    params = TextParams(text = textHtml),
    metadata = H5PMetadata(contentType = "Text", title = title, extraTitle = title),
    subContentId = Uuids.next()
)

fun tableElement(tableHtml: String, title: String = "Untitled Table") = H5PTableElement(
    params = TextParams(text = tableHtml),
    metadata = H5PMetadata(contentType = "Table", title = title, extraTitle = title),
    subContentId = Uuids.next()
)

fun imageElement(
    imagePath: String,
    width: Int = 1920,
    height: Int = 1080,
    altText: String,
    hoverTitle: String = altText,
    metadataTitle: String = "Untitled Image",
    mimeType: String = "image/png"
) = H5PImageElement(
    params = ImageParams(
        alt = altText,
        title = hoverTitle,
        decorative = false,
        file = H5PImageFile(
            path = imagePath,
            mime = mimeType,
            width = width,
            height = height,
            copyright = H5PImageCopyright(license = "U")
        )
    ),
    metadata = H5PMetadata(contentType = "Image", title = metadataTitle, extraTitle = metadataTitle),
    subContentId = Uuids.next()
)

/**
 * A multiple-choice question. [answers] keeps the order the author wrote, since MultiChoice renders
 * them in place; which of them are correct is carried per answer rather than by position.
 */
fun multiChoiceElement(
    question: String,
    answers: List<MultiChoiceAnswer>,
    title: String = "Untitled Multiple Choice"
) = H5PMultiChoiceElement(
    params = MultiChoiceParams(question = question, answers = answers),
    metadata = H5PMetadata(contentType = "Multiple Choice", title = title, extraTitle = title),
    subContentId = Uuids.next()
)

/**
 * A single-choice question, as a set of one - the library has no smaller unit.
 *
 * [correct] is passed apart from [distractors] because the answer key *is* the order here: the
 * library reads entry zero as the right one and shuffles the rest for display (see [SingleChoice]).
 */
fun singleChoiceElement(
    question: String,
    correct: String,
    distractors: List<String>,
    title: String = "Untitled Single Choice"
) = H5PSingleChoiceSetElement(
    params = SingleChoiceSetParams(
        choices = listOf(
            SingleChoice(
                question = question,
                answers = listOf(correct) + distractors,
                subContentId = Uuids.next()
            )
        )
    ),
    metadata = H5PMetadata(contentType = "Single Choice Set", title = title, extraTitle = title),
    subContentId = Uuids.next()
)

/**
 * One row of the pseudo-table: [cells] holds the content of each column, stacked vertically inside
 * it. At most four columns - more is not representable, see [H5PRowElement].
 */
fun rowElement(cells: List<List<H5PElement>>, title: String = "Untitled Row"): H5PRowElement {
    require(cells.isNotEmpty() && cells.size <= MAX_ROW_COLUMNS) {
        "a row holds 1..$MAX_ROW_COLUMNS columns, got ${cells.size}"
    }

    // H5P.Row throws away every width in the row unless they add up to exactly 100, so the
    // remainder of the division goes to the leftmost columns instead of being lost.
    val base = 100 / cells.size
    val remainder = 100 - base * cells.size

    val columns = cells.mapIndexed { index, contents ->
        H5PRowColumn(
            width = base + if (index < remainder) 1 else 0,
            content = H5PRowColumnElement(
                params = RowColumnParams(content = contents),
                metadata = H5PMetadata(contentType = "Column", title = title, extraTitle = title),
                subContentId = Uuids.next()
            )
        )
    }

    return H5PRowElement(
        params = RowParams(columns = columns),
        metadata = H5PMetadata(contentType = "Row", title = title, extraTitle = title),
        subContentId = Uuids.next()
    )
}

/** `H5P.Row`'s `columns` list is capped at four by its semantics. */
const val MAX_ROW_COLUMNS = 4