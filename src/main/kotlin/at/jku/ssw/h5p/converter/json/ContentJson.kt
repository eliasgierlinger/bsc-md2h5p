package at.jku.ssw.h5p.converter.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.uuid.Uuid

/**
 * Corresponds to content/content.json in H5P archive
 */
@Serializable
data class H5PBook(
    val showCoverPage: Boolean = false,
    val bookCover: H5BookCover = H5BookCover(),
    val chapters: List<H5PChapter>,
    val behaviour: H5PBehaviour = H5PBehaviour(),

    val read: String = "Read",
    val displayTOC: String = "Display 'Table of contents'",
    val hideTOC: String = "Hide 'Table of contents'",
    val nextPage: String = "Next page",
    val previousPage: String = "Previous page",
    val chapterCompleted: String = "Page completed!",
    val partCompleted: String = "@pages of @total completed",
    val incompleteChapter: String = "Incomplete page",
    val navigateToTop: String = "Navigate to the top",
    val markAsFinished: String = "I have finished this page",
    val fullscreen: String = "Fullscreen",
    val exitFullscreen: String = "Exit fullscreen",
    val bookProgressSubtext: String = "@count of @total pages",
    val interactionsProgressSubtext: String = "@count of @total interactions",
    val submitReport: String = "Submit Report",
    val restartLabel: String = "Restart",
    val summaryHeader: String = "Summary",
    val allInteractions: String = "All interactions",
    val unansweredInteractions: String = "Unanswered interactions",
    val scoreText: String = "@score / @maxscore",
    val leftOutOfTotalCompleted: String = "@left of @max interactions completed",
    val noInteractions: String = "No interactions",
    val score: String = "Score",
    val summaryAndSubmit: String = "Summary &amp; submit",
    val noChapterInteractionBoldText: String = "You have not interacted with any pages.",
    val noChapterInteractionText: String = "You have to interact with at least one page before you can see the summary.",
    val yourAnswersAreSubmittedForReview: String = "Your answers are submitted for review!",
    val bookProgress: String = "Book progress",
    val interactionsProgress: String = "Interactions progress",
    val totalScoreLabel: String = "Total score",
    val a11y: H5PAccessibility = H5PAccessibility(),
    // New in InteractiveBook 1.15.
    val page: String = "Page",
    val next: String = "Next",
    val previous: String = "Previous"
)

@Serializable
data class H5PAccessibility(
    val progress: String = "Page @page of @total.",
    val menu: String = "Toggle navigation menu",
)

@Serializable
data class H5BookCover(
    val coverDescription: String = ""
)

@Serializable
data class H5PBehaviour(
    val baseColor: String = "#1768c4",
    val defaultTableOfContents: Boolean = true,
    val progressIndicators: Boolean = true,
    val progressAuto: Boolean = true,
    val displaySummary: Boolean = true,
    val enableRetry: Boolean = true
)

@Serializable
data class H5PChapter(
    val params: H5PColumnParams,
    val library: String = "H5P.Column 1.22",
    val subContentId: Uuid,
    val metadata: H5PMetadata
)

@Serializable
data class H5PColumnParams(
    val content: List<H5PColumnContent>
)

@Serializable
data class H5PColumnContent(
    val content: H5PElement,
    val useSeparator: H5PSeparator = H5PSeparator.AUTO
)

@Serializable
enum class H5PSeparator {
    @SerialName("auto")
    AUTO,

    @SerialName("disabled")
    DISABLED,

    @SerialName("enabled")
    ENABLED
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("library")
@Serializable
sealed class H5PElement {
    abstract val metadata: H5PMetadata
    abstract val subContentId: Uuid
}

@Serializable
@SerialName("H5P.AdvancedText 1.1")
data class H5PTextElement(
    val params: TextParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
@SerialName("H5P.Image 1.1")
data class H5PImageElement(
    val params: ImageParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
@SerialName("H5P.Table 1.2")
data class H5PTableElement(
    val params: TextParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

/**
 * A row of side-by-side columns, used to rebuild a table that contains an image - `img` is allowed
 * in no H5P text field, so a picture in a table cell can only be a content block of its own.
 *
 * `H5P.Row` takes at most four columns, and its columns hold `H5P.RowColumn` and nothing else -
 * rows cannot nest, so a cell is a vertical stack, never a horizontal split.
 */
@Serializable
@SerialName("H5P.Row 1.3")
data class H5PRowElement(
    val params: RowParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
data class RowParams(
    val columns: List<H5PRowColumn>
)

/**
 * [width] is a percentage. H5P.Row overwrites every width in the row as soon as they do not add up
 * to exactly 100, so they are handed out to add up - otherwise the columns of two rows would be
 * distributed differently and the pseudo-table would not line up.
 */
@Serializable
data class H5PRowColumn(
    val width: Int,
    val content: H5PElement
)

/** Column-like container that is the only thing a [H5PRowElement] column can hold. */
@Serializable
@SerialName("H5P.RowColumn 1.3")
data class H5PRowColumnElement(
    val params: RowColumnParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
data class RowColumnParams(
    /**
     * The elements themselves, **not** wrapped in a `{ "content": ... }` object the way
     * [H5PColumnParams] wraps them. The difference is not a style choice, it is the single-field
     * group rule: `H5PContentValidator::validateGroup` (`h5p.classes.php:4705-4718`) says *"Groups
     * with just one field are compressed in the editor to only output the child content"*, and
     * `H5P.RowColumn`'s list item is a group holding exactly one field (`content`), while
     * `H5P.Column`'s holds two (`content` and `useSeparator`). The two libraries read their params
     * accordingly - `h5p-column.js` in `H5P.Column` does `addRunnable(content.content, ...)`, the
     * copy shipped with `H5P.RowColumn` does `if (content.library === undefined) continue`. Wrapping
     * here is therefore silent data loss: the row renders with the right number of empty columns.
     */
    val content: List<H5PElement>
)

@Serializable
data class H5PMetadata(
    val contentType: String,
    val license: String = "U",
    val title: String,
    val extraTitle: String,
    val authors: List<String> = emptyList(),
    val changes: List<String> = emptyList()
)

@Serializable
data class TextParams(
    val text: String
)

@Serializable
data class ImageParams(
    val decorative: Boolean = false,
    val contentName: String = "Image",
    val expandImage: String = "Expand Image",
    val minimizeImage: String = "Minimize Image",
    val file: H5PImageFile,
    val alt: String,
    val title: String
)

@Serializable
data class H5PImageFile(
    val path: String,
    val mime: String,
    val copyright: H5PImageCopyright,
    val width: Int,
    val height: Int
)

@Serializable
data class H5PImageCopyright(
    val license: String = "U"
)
// --- quizzes -------------------------------------------------------------------------------------
//
// The two libraries a custom fenced block can produce (see `converter/render/CustomRenderer.kt`).
// Their params are written out in full rather than left to the platform's defaults: a default is
// only filled in by a *validator*, and a package is expected to play in a bare H5P player too.
//
// Both are pinned to the exact versions `H5P.Column 1.22`'s semantics name in its `options` list -
// `H5P.MultiChoice 1.16` and `H5P.SingleChoiceSet 1.11`. `validateLibrary` deletes content built on
// any other version, so these strings are a contract with the template, not a preference.

@Serializable
@SerialName("H5P.MultiChoice 1.16")
data class H5PMultiChoiceElement(
    val params: MultiChoiceParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
data class MultiChoiceParams(
    val question: String,
    val answers: List<MultiChoiceAnswer>,
    val media: MultiChoiceMedia = MultiChoiceMedia(),
    val overallFeedback: List<FeedbackRange> = listOf(FeedbackRange()),
    @SerialName("UI") val ui: MultiChoiceUi = MultiChoiceUi(),
    val behaviour: MultiChoiceBehaviour = MultiChoiceBehaviour(),
    val confirmCheck: ConfirmDialog = ConfirmDialog.check(),
    val confirmRetry: ConfirmDialog = ConfirmDialog.retry()
)

@Serializable
data class MultiChoiceAnswer(
    val text: String,
    val correct: Boolean,
    val tipsAndFeedback: TipsAndFeedback = TipsAndFeedback()
)

@Serializable
data class TipsAndFeedback(
    val tip: String = "",
    val chosenFeedback: String = "",
    val notChosenFeedback: String = ""
)

/**
 * Two fields, so it is *not* flattened away the way a one-field group is - see [RowColumnParams].
 * `type` is an optional library and stays absent: a quiz written in Markdown has no media.
 */
@Serializable
data class MultiChoiceMedia(
    val disableImageZooming: Boolean = false
)

/** The one range that covers the whole score, which is what an editor writes when nobody adds any. */
@Serializable
data class FeedbackRange(
    val from: Int = 0,
    val to: Int = 100
)

@Serializable
data class MultiChoiceUi(
    val checkAnswerButton: String = "Check",
    val submitAnswerButton: String = "Submit",
    val showSolutionButton: String = "Show solution",
    val tryAgainButton: String = "Retry",
    val tipsLabel: String = "Show tip",
    val scoreBarLabel: String = "You got :num out of :total points",
    val tipAvailable: String = "Tip available",
    val feedbackAvailable: String = "Feedback available",
    val readFeedback: String = "Read feedback",
    val wrongAnswer: String = "Wrong answer",
    val correctAnswer: String = "Correct answer",
    val shouldCheck: String = "Should have been checked",
    val shouldNotCheck: String = "Should not have been checked",
    val noInput: String = "Please answer before viewing the solution",
    val a11yCheck: String =
        "Check the answers. The responses will be marked as correct, incorrect, or unanswered.",
    val a11yShowSolution: String =
        "Show the solution. The task will be marked with its correct solution.",
    val a11yRetry: String =
        "Retry the task. Reset all responses and start the task over again."
)

/**
 * [type] is pinned to `multi` rather than left at the semantics' `auto` default, which would draw
 * radio buttons whenever exactly one answer happens to be correct. That is the wrong default here:
 * `singlechoice` already exists and is a different interaction, so a `multiplechoice` block that
 * currently has one starred line would render as the *other* syntax - and would silently switch back
 * to checkboxes the moment a second answer was starred. Checkboxes always, so the syntax the author
 * wrote is the widget the reader sees.
 *
 * [randomAnswers] is off for the same reason: the order of the answers is part of what the author
 * wrote, and MultiChoice, unlike SingleChoiceSet, does not need a shuffle to hide the key.
 */
@Serializable
data class MultiChoiceBehaviour(
    val enableRetry: Boolean = true,
    val enableSolutionsButton: Boolean = true,
    val enableCheckButton: Boolean = true,
    val type: String = "multi",
    val singlePoint: Boolean = false,
    val randomAnswers: Boolean = false,
    val showSolutionsRequiresInput: Boolean = true,
    val confirmCheckDialog: Boolean = false,
    val confirmRetryDialog: Boolean = false,
    val autoCheck: Boolean = false,
    val passPercentage: Int = 100,
    val showScorePoints: Boolean = true
)

@Serializable
data class ConfirmDialog(
    val header: String,
    val body: String,
    val cancelLabel: String = "Cancel",
    val confirmLabel: String
) {
    companion object {
        fun check() = ConfirmDialog(
            header = "Finish ?",
            body = "Are you sure you wish to finish ?",
            confirmLabel = "Finish"
        )

        fun retry() = ConfirmDialog(
            header = "Retry ?",
            body = "Are you sure you wish to retry ?",
            confirmLabel = "Confirm"
        )
    }
}

/**
 * A set of single-answer questions. One Markdown block produces a set of exactly one - the library
 * has no single-question form, and merging consecutive blocks into one set was rejected because it
 * would make a block's meaning depend on its neighbours (D30).
 */
@Serializable
@SerialName("H5P.SingleChoiceSet 1.11")
data class H5PSingleChoiceSetElement(
    val params: SingleChoiceSetParams,
    override val metadata: H5PMetadata,
    override val subContentId: Uuid
) : H5PElement()

@Serializable
data class SingleChoiceSetParams(
    val choices: List<SingleChoice>,
    val overallFeedback: List<FeedbackRange> = listOf(FeedbackRange()),
    val behaviour: SingleChoiceBehaviour = SingleChoiceBehaviour(),
    val l10n: SingleChoiceL10n = SingleChoiceL10n()
)

/**
 * [answers] is ordered, and the order carries the answer key: `single-choice.js` marks entry zero
 * `correct: i === 0` and then calls `H5P.shuffleArray` on the list, so the correct answer is written
 * first and the reader still sees them in a random order. The author's order is therefore not kept,
 * and where the `*` sat in the Markdown does not show.
 */
@Serializable
data class SingleChoice(
    val question: String,
    val answers: List<String>,
    val subContentId: Uuid
)

@Serializable
data class SingleChoiceBehaviour(
    val autoContinue: Boolean = true,
    val timeoutCorrect: Int = 2000,
    val timeoutWrong: Int = 3000,
    val soundEffectsEnabled: Boolean = true,
    val enableRetry: Boolean = true,
    val enableSolutionsButton: Boolean = true,
    val passPercentage: Int = 100
)

@Serializable
data class SingleChoiceL10n(
    val nextButtonLabel: String = "Next question",
    val showSolutionButtonLabel: String = "Show solution",
    val retryButtonLabel: String = "Retry",
    val solutionViewTitle: String = "Solution list",
    val correctText: String = "Correct!",
    val incorrectText: String = "Incorrect!",
    val shouldSelect: String = "Should have been selected",
    val shouldNotSelect: String = "Should not have been selected",
    val muteButtonLabel: String = "Mute feedback sound",
    val closeButtonLabel: String = "Close",
    val slideOfTotal: String = "Slide :num of :total",
    val scoreBarLabel: String = "You got :num out of :total points",
    val solutionListQuestionNumber: String = "Question :num",
    val a11yShowSolution: String =
        "Show the solution. The task will be marked with its correct solution.",
    val a11yRetry: String =
        "Retry the task. Reset all responses and start the task over again."
)
