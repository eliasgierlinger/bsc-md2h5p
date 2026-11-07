package at.jku.ssw.h5p.merge

import at.jku.ssw.h5p.converter.NUMBER_PREFIX
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How alike two blocks are, used to recognize a block again after the Markdown behind it changed.
 */
object Similarity {

    /**
     * At or above this two blocks are taken to be the same block, changed. Low enough that a
     * rewritten paragraph is still recognized, high enough that two unrelated short paragraphs are
     * not - which is cheap to keep, since candidates must also be of the same library and must not
     * cross the alignment.
     */
    const val MATCH = 0.35

    /**
     * At or above this an *unaligned* fresh block counts as another piece of a block that the
     * Markdown split - inserting an image mid-paragraph makes one block become `text / image /
     * text`. Deliberately far lower than [MATCH]: the block is only a candidate if it is unaligned
     * and directly adjacent, so the score only has to rule out something unrelated.
     */
    const val SPLIT = 0.15

    /**
     * The most a similar *file name* can add to a chapter's score. Below [MATCH] on purpose: a name
     * can support a match that the content already half makes, and can never make one on its own -
     * `chapter2.md` and `chapter3.md` are as alike as two names get and are not the same chapter.
     */
    const val NAME_BONUS = 0.3

    /**
     * How alike, from 0 to 1. Sorensen-Dice over the adjacent word pairs: an intersection of pairs rather than an edit distance, because a
     * chapter compares every old block against every new one and Levenshtein would make that
     * quadratic in the text length as well as in the block count.
     *
     * Word *pairs* rather than words, so that a paragraph is not matched by its vocabulary alone;
     * for a block of one word there are none, and the words themselves are compared instead.
     */
    fun of(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        val left = shingles(a)
        val right = shingles(b)
        if (left.isEmpty() || right.isEmpty()) return if (a == b) 1.0 else 0.0
        return dice(left, right)
    }

    /**
     * Sorensen-Dice proper: each pair on the left may be answered by one pair on the right.
     */
    private fun dice(left: List<String>, right: List<String>): Double {
        val unclaimed = HashMap<String, Int>()
        for (shingle in right) unclaimed.merge(shingle, 1, Int::plus)

        var common = 0
        for (shingle in left) {
            val available = unclaimed[shingle] ?: 0
            if (available > 0) {
                unclaimed[shingle] = available - 1
                common++
            }
        }
        return 2.0 * common / (left.size + right.size)
    }

    /**
     * The words of one element, as a reader would see them.
     *
     * Only `params` is read - `metadata` holds titles like "Untitled Text" that every block of a
     * kind shares, which would make everything look alike. Tags are stripped **before** anything
     * else: a four-cell table is some five hundred characters of JSON, nearly all of it the per-cell
     * inline styles D9 puts there, and about six words of actual text.
     *
     * [CHROME] is the same argument one level down, and a quiz is where it stops being a detail:
     * `H5P.MultiChoice`'s params are a question and its answers plus some ninety words of button
     * labels, feedback text and behavior flags that are identical in every MultiChoice ever
     * written. Two entirely unrelated questions measure **0.99** alike with those left in and 0.0
     * with them out, so without this every quiz in a book matches every other one.
     */
    fun textOf(element: JsonObject): String {
        val params = element["params"] ?: return ""
        val out = StringBuilder()
        collectStrings(params, out)
        return out.toString()
            .replace(TAG, " ")
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim()
    }

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Params a library declares for its own chrome rather than for the author: button labels,
     * accessibility strings, feedback ranges, behavior flags. Skipped by name at any depth, the way
     * `Markers.hashOf` skips the fields the platform owns - and for the same kind of reason, that a
     * field nobody wrote says nothing about what a block *is*.
     *
     * A list rather than a rule because there is no rule: which fields are chrome is a fact about
     * each library's semantics. It only has to cover what this converter emits - alignment compares
     * Markdown-derived blocks, and those are the ones it wrote itself.
     */
    private val CHROME = setOf(
        "l10n", "UI", "behaviour", "overallFeedback", "confirmCheck", "confirmRetry", "media",
        "tipsAndFeedback"
    )

    private fun collectStrings(node: JsonElement, out: StringBuilder) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                if (key !in CHROME) collectStrings(value, out)
            }

            is JsonArray -> node.forEach { collectStrings(it, out) }
            is JsonPrimitive -> if (node.isString) out.append(node.content).append(' ')
        }
    }

    private fun shingles(text: String): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return words
        return (0 until words.size - 1).map { "${words[it]} ${words[it + 1]}" }
    }

    /**
     * How alike two Markdown file names are, once each is reduced to what it actually names.
     *
     * Compared by *character* pairs rather than word pairs: a file name is a handful of words, often
     * one, so word pairs would usually not exist at all.
     */
    fun ofFileName(a: String, b: String): Double {
        val left = simpleName(a)
        val right = simpleName(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val l = charShingles(left)
        val r = charShingles(right)
        if (l.isEmpty() || r.isEmpty()) return 0.0
        return dice(l, r)
    }

    private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")

    private fun simpleName(name: String): String =
        name.substringBeforeLast('.')
            .replaceFirst(NUMBER_PREFIX, "")
            .lowercase()
            .replace(NOT_ALPHANUMERIC, " ")
            .trim()

    private fun charShingles(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        return (0 until text.length - 1).map { text.substring(it, it + 2) }
    }
}

/**
 * How alike two content blocks are: a different library is never the same block, an unchanged one is
 * recognized by its hash without looking at any text, and everything else is compared as prose.
 */
internal fun blockScore(old: JsonObject, fresh: JsonObject): Double {
    if (old.libraryName() != fresh.libraryName()) return 0.0
    if (Markers.hashOf(old) == Markers.hashOf(fresh)) return 1.0
    return Similarity.of(Similarity.textOf(old), Similarity.textOf(fresh))
}

/**
 * How alike two chapters are: the Markdown file they came from, then their title, then all of their
 * blocks read as one text with a bonus for a file name that is nearly the same. The first two are
 * what D26 already matched on; the rest is what keeps a chapter whose `.md` was renamed *and* whose
 * heading was changed in the same round from reading as one chapter deleted and another added, which
 * used to strand its additions in a rump chapter.
 *
 * A file name that is *equal once normalized* is a tier of its own: renumbering a book, or swapping
 * `_` for a space, changes every file name without changing a single chapter, and the converter
 * already discards exactly that much when it derives a title from a file name. A name that is merely
 * **similar** is only a bonus on top of the text, can never reach [Similarity.MATCH] by itself, and
 * is not consulted at all until the text has shown [Similarity.SPLIT] of evidence - `3_Basics.md`
 * and `4_Basics2.md` are as alike as two names get and are two different chapters.
 */
internal fun chapterScore(old: JsonObject, fresh: JsonObject): Double {
    val oldSource = Markers.markerOf(old)?.key
    val freshSource = Markers.markerOf(fresh)?.key
    if ((oldSource != null) && (oldSource == freshSource)) return 1.0

    val oldTitle = old.metadataTitle()
    if (oldTitle.isNotEmpty() && oldTitle == fresh.metadataTitle()) return 0.9

    val name = if (oldSource != null && freshSource != null) {
        Similarity.ofFileName(oldSource, freshSource)
    } else {
        0.0
    }
    if (name == 1.0) return 0.85

    val text = Similarity.of(chapterText(old), chapterText(fresh))
    if (text < Similarity.SPLIT) return text
    return minOf(1.0, text + Similarity.NAME_BONUS * name)
}

private fun chapterText(chapter: JsonObject): String =
    chapter.contentEntries().joinToString(" ") { Similarity.textOf(it.element()) }

private fun JsonObject.libraryName(): String =
    (this["library"] as? JsonPrimitive)?.content.orEmpty()
