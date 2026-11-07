package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.json.MultiChoiceAnswer
import at.jku.ssw.h5p.converter.multiChoiceElement
import at.jku.ssw.h5p.converter.singleChoiceElement
import org.commonmark.node.FencedCodeBlock

/**
 * The constructs that are written as a fenced code block but are not code.
 *
 * Markdown has no syntax for a quiz, and inventing one is not an option either: whatever md2h5p
 * accepts still has to render as *something* in every other Markdown tool the author uses, and a
 * fenced block is the one construct that both survives untouched everywhere and carries a free-form
 * label of its own. So the label is the extension point - `singlechoice` and `multiplechoice` are
 * read as interactive content, anything else stays a code block (D30).
 *
 * This class exists so that stays true of the *next* one as well: [SYNTAXES] is the whole list of
 * labels that mean something, and [MarkdownChunker] asks it rather than knowing any of them.
 *
 * Everything produced here is a **content block**, never markup - `H5P.MultiChoice` is a library of
 * its own, so a quiz cannot be written into a text field any more than an image can, and it goes
 * through [HtmlWriter.place] for the same reason and by the same route (see [RenderContext]).
 */
class CustomRenderer(private val warnings: MutableCollection<Warning>) {

    /** Whether [info] names a custom construct - if not, the block is code and is highlighted. */
    fun handles(info: String?): Boolean = syntaxOf(info) != null

    /**
     * The block for [fenced], or null if what it holds cannot be made into one.
     *
     * Null is not an error path: the caller renders the block as ordinary code instead, so a
     * question that is missing its answers still shows the reader exactly what the author wrote,
     * with a warning naming what was wrong. Rendering half a quiz would be worse than rendering none.
     */
    fun blockFor(fenced: FencedCodeBlock): PlacedBlock? {
        val syntax = syntaxOf(fenced.info) ?: return null
        val lines = fenced.literal.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val question = lines.firstOrNull()
        if (question == null) {
            warnings.add(reject(syntax, "it is empty"))
            return null
        }

        val options = lines.drop(1).map(::parseOption)
        if (options.size < 2) {
            warnings.add(
                reject(
                    syntax,
                    "a question needs at least two answers to choose between, and \"$question\" has " +
                        "${options.size}"
                )
            )
            return null
        }

        val correct = options.filter { it.correct }
        if (correct.isEmpty()) {
            warnings.add(
                reject(
                    syntax,
                    "no answer to \"$question\" is marked correct - put a * in front of the ones " +
                        "that are"
                )
            )
            return null
        }

        return when (syntax) {
            Syntax.SINGLE_CHOICE -> singleChoice(question, options, correct)
            Syntax.MULTIPLE_CHOICE -> multipleChoice(question, options)
        }
    }

    private fun singleChoice(
        question: String,
        options: List<Option>,
        correct: List<Option>
    ): PlacedBlock {
        if (correct.size > 1) {
            // Not a rejection: the question is answerable, it just says more than the syntax can
            // carry. The author most likely wanted ```multiplechoice, and the warning says so.
            warnings.add(
                Warning(
                    "A singlechoice block marks ${correct.size} answers to \"$question\" as correct, " +
                        "but a single choice question has exactly one right answer. The first one " +
                        "is used and the rest become wrong answers. Use multiplechoice for a " +
                        "question with several right answers."
                )
            )
        }

        val key = correct.first()
        return PlacedBlock(
            element = singleChoiceElement(
                question = paragraph(question),
                correct = paragraph(key.text),
                distractors = options.filter { it !== key }.map { paragraph(it.text) },
                title = question
            ),
            placeholder = "❓ $question",
            source = question
        )
    }

    private fun multipleChoice(question: String, options: List<Option>) = PlacedBlock(
        element = multiChoiceElement(
            question = paragraph(question),
            answers = options.map { MultiChoiceAnswer(text = paragraph(it.text), correct = it.correct) },
            title = question
        ),
        placeholder = "❓ $question",
        source = question
    )

    /**
     * One answer line. A leading `*` marks it correct; `\*` is how an answer that really does start
     * with an asterisk is written.
     */
    private fun parseOption(line: String): Option {
        val correct = line.startsWith(CORRECT_MARKER)
        val text = if (correct) line.removePrefix(CORRECT_MARKER).trim() else line

        // Unescaping happens after the marker is taken off, not instead of it, so that a correct
        // answer which itself begins with an asterisk (`*\*.md`) is both.
        return Option(if (text.startsWith(ESCAPED_MARKER)) text.substring(1) else text, correct)
    }

    private fun reject(syntax: Syntax, reason: String) = Warning(
        "A ${syntax.label} block was left as a code block because $reason."
    )

    private data class Option(val text: String, val correct: Boolean)

    private enum class Syntax(val label: String) {
        SINGLE_CHOICE("singlechoice"),
        MULTIPLE_CHOICE("multiplechoice")
    }

    private companion object {
        val SYNTAXES = Syntax.entries.associateBy { it.label }

        const val CORRECT_MARKER = "*"

        /** A literal asterisk at the start of an answer, escaped the way Markdown escapes one. */
        const val ESCAPED_MARKER = "\\*"

        /**
         * The info string may carry more than the label (```singlechoice id=q1), and only its first
         * word ever names the construct - the same rule [CodeHighlighter] applies to a language.
         */
        fun syntaxOf(info: String?): Syntax? {
            val label = info?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore(' ')?.lowercase()
            return SYNTAXES[label]
        }

        /**
         * H5P's question and answer fields are HTML, and an editor writes a paragraph into each.
         *
         * The text is escaped rather than parsed: inline Markdown inside a quiz would mean running
         * the inline parser over a literal the fence deliberately kept literal, and an answer reading
         * `2 * 3 < 10` has to survive the sanitizer unchanged more than it needs to be able to hold
         * bold.
         */
        fun paragraph(text: String) = "<p>${escapeHtml(text)}</p>"
    }
}
