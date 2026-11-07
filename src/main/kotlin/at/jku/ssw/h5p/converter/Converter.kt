package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.json.H5BookCover
import at.jku.ssw.h5p.converter.json.H5PInfo
import at.jku.ssw.h5p.converter.render.escapeHtml
import at.jku.ssw.h5p.merge.Markers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

data class ConversionResult(val file: File, val warnings: List<Warning>)

fun convert(rootDir: File, inputSource: InputSource, title: String, subfolder: String? = null): ConversionResult {
    val folderResolution = resolveWorkingDir(rootDir, subfolder)
    val workingDir = folderResolution.workingDir

    val files = workingDir.listFiles()?.toList() ?: emptyList()

    val pathPolicy = PathPolicy.forSource(inputSource, rootDir = rootDir, workingDir = workingDir)
    val images = ImageResolver(pathPolicy)

    val rawBook = createBookFromFiles(files, images)

    val warnings = folderResolution.warnings + rawBook.warnings + images.warnings

    val finalBook = rawBook.book.copy(
        showCoverPage = true,
        bookCover = H5BookCover(
            // The title is free text - typed into the web form, or taken from a folder or repo name -
            // so it goes through the same escaping as any other text that becomes markup.
            coverDescription = "<h2>${escapeHtml(title)}</h2>"
        )
    )

    val h5pInfo = H5PInfo(title = title)

    // Each chapter and each block records which Markdown file it came from and what it looked like,
    // so that a package that has since been edited in H5P can be merged with a later conversion
    // (D26, D27). The marker is written into the book *after* it is serialized, through the same
    // serializer that writes content.json - the recorded hashes have to be hashes of exactly what
    // ends up in the file, and nothing in the renderer or the content model knows this exists.
    val markedBook = Markers.inject(
        book = Packager.json.encodeToJsonElement(finalBook).jsonObject,
        sourceFiles = rawBook.chapters.map { it.file.name }
    )

    val resultFile = Packager.pack(
        contentJson = Packager.json.encodeToString(JsonElement.serializer(), markedBook),
        h5pInfo = h5pInfo,
        outputFileName = title,
        images = images.images
    )

    return ConversionResult(resultFile, warnings)
}
