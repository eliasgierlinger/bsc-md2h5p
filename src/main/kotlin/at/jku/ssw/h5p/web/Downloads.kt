package at.jku.ssw.h5p.web

import java.io.File
import java.util.UUID

/**
 * The packages produced by the running server, addressed by an unguessable token.
 *
 * A conversion answers with JSON holding the warnings
 * and a link, so the browser can show both. The link has to name the file somehow, and naming it by
 * path would mean accepting a client-supplied path into [at.jku.ssw.h5p.AppConfig.outputDir]. A
 * token handed out by the server avoids that question entirely.
 *
 * The map is bounded: a long-running server would otherwise keep a reference to every package it
 * ever built. Evicting the token does not delete the file - only the link to it goes stale. The file
 * is on a clock of its own: [at.jku.ssw.h5p.WorkDirs.createOutputDir] deletes the packages older
 * than a quarter of an hour whenever it makes room for a new one, so a token can also outlive what
 * it points at, which is why the download route checks that what it found is still a file.
 */
object Downloads {

    private const val MAX_ENTRIES = 64

    private val byToken = object : LinkedHashMap<String, File>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, File>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun register(file: File): String = UUID.randomUUID().toString().also { byToken[it] = file }

    @Synchronized
    fun find(token: String?): File? = token?.let { byToken[it] }
}
