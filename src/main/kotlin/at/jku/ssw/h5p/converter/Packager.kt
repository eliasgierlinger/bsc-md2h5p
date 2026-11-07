package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.AppConfig
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.json.H5PInfo
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Packager {
    internal val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    /**
     * [contentJson] arrives already serialized: the merge markers are written into the book after it
     * has been turned into JSON (D27), and what lands in the zip has to be those very bytes - the
     * recorded hashes describe them.
     */
    fun pack(
        contentJson: String,
        h5pInfo: H5PInfo,
        outputFileName: String,
        images: List<PackedImage> = emptyList()
    ): File {
        val h5pFile = File(WorkDirs.createOutputDir(), "${sanitizeFileName(outputFileName)}.h5p")

        val h5pJsonString = json.encodeToString(h5pInfo)

        ZipOutputStream(FileOutputStream(h5pFile)).use { zos ->
            ZipInputStream(AppConfig.templateZip()).use { template ->
                var entry = template.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        when (entry.name) {
                            "h5p.json" -> zos.write(h5pJsonString.toByteArray())
                            "content/content.json" -> zos.write(contentJson.toByteArray())
                            else -> template.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                    entry = template.nextEntry
                }
            }

            images.forEach { image ->
                if (image.file.isFile) {
                    zos.putNextEntry(ZipEntry("content/images/${image.packedName}"))
                    image.file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        return h5pFile
    }

    /**
     * The title is free text - since it can be typed into the web form, it can hold anything at all.
     * Everything outside the allow-list becomes `_`, which is what keeps a separator from making this
     * a path rather than a name; runs of them collapse so a title like "Notes / Part 1" still reads
     * as one. Taking `File(name).name` instead would be safe too, but it *drops* everything before a
     * separator, so that title would end up called "1".
     */
    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifEmpty { "output" }
    }
}