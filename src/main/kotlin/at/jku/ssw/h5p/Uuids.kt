package at.jku.ssw.h5p

import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Single source of the subContentId UUIDs in generated H5P content. Normally they are freshly
 * random on every conversion (which is what H5P expects), but golden tests switch to a fixed
 * sequence so that two runs over the same input produce byte-identical content.json.
 */
object Uuids {
    private var fixedSequence: Random? = null

    fun startFixedSequence() {
        fixedSequence = Random(seed = 42)
    }

    fun next(): Uuid {
        val sequence = fixedSequence ?: return Uuid.random()
        return Uuid.fromLongs(sequence.nextLong(), sequence.nextLong())
    }
}
