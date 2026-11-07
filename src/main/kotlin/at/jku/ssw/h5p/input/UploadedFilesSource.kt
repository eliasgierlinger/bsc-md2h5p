package at.jku.ssw.h5p.input

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.isWithinDir
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File

/**
 * [title] is the one the user typed next to the files, if they typed one. [existingPackage] is a
 * previously generated `.h5p` the conversion should be merged into, if one was attached (D26).
 */
data class UploadResult(
    val sourceDir: File,
    val warnings: List<Warning>,
    val title: String?,
    val existingPackage: File? = null
)

object UploadedFilesSource {
    suspend fun receive(multipart: MultiPartData): UploadResult {
        val targetDir = WorkDirs.createTempDir("md2h5p-upload-")
        val targetCanonical = targetDir.canonicalFile
        val warnings = mutableListOf<Warning>()
        var title: String? = null
        var existingPackage: File? = null

        try {
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem if part.name == EXISTING_PACKAGE_FIELD -> {
                        val target = File(targetDir, "existing-package.h5p")
                        part.provider().toInputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (target.length() > 0) existingPackage = target
                    }

                    is PartData.FileItem -> {
                        val relativePath = part.originalFileName ?: "file"
                        val destination = File(targetDir, relativePath).canonicalFile

                        if (isWithinDir(targetCanonical, destination)) {
                            destination.parentFile.mkdirs()
                            part.provider().toInputStream().use { input ->
                                destination.outputStream().use { output -> input.copyTo(output) }
                            }
                        } else {
                            warnings.add(Warning("Uploaded file \"$relativePath\" is outside the allowed folder and was skipped."))
                        }
                    }

                    is PartData.FormItem -> if (part.name == "title") title = part.value.trim().ifBlank { null }

                    else -> Unit
                }
                part.release()
            }
        } catch (e: Throwable) {
            WorkDirs.discard(targetDir)
            throw e
        }

        return UploadResult(targetDir, warnings, title, existingPackage)
    }

    /** The multipart field the existing package arrives in; the same name the form uses. */
    const val EXISTING_PACKAGE_FIELD = "existingPackage"
}
