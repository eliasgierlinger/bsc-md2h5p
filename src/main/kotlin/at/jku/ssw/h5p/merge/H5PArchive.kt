package at.jku.ssw.h5p.merge

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A `.h5p` opened for reading. Entries are streamed straight out of the zip and, when merging, straight
 * into the next one
 */
class H5PArchive(file: File) : Closeable {

    private val zip = ZipFile(file)

    /** Every file entry, safe ones only, in a stable order. */
    val names: List<String> =
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name.replace('\\', '/') }
            .filter { isSafeEntryName(it) }
            .sorted()
            .toList()

    operator fun contains(name: String): Boolean = name in names

    fun bytes(name: String): ByteArray? =
        zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

    fun text(name: String): String? = bytes(name)?.toString(Charsets.UTF_8)

    override fun close() = zip.close()

    companion object {
        /**
         * An absolute name, or one that climbs, would place a file outside the package when it is
         * unpacked by whatever opens it next
         */
        fun isSafeEntryName(name: String): Boolean =
            name.isNotEmpty() &&
                !name.startsWith("/") &&
                !name.contains(":") &&
                name.split('/').none { it == ".." }
    }
}

/** Writes a `.h5p` */
class H5PArchiveWriter(out: OutputStream) : Closeable {

    private val zip = ZipOutputStream(out)
    private val written = mutableSetOf<String>()

    fun add(name: String, bytes: ByteArray) {
        if (!H5PArchive.isSafeEntryName(name) || !written.add(name)) return
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    fun add(name: String, text: String) = add(name, text.toByteArray(Charsets.UTF_8))

    override fun close() = zip.close()
}
