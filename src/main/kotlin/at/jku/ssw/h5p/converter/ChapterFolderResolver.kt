package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import java.io.File

data class WorkingDirResolution(val workingDir: File, val warnings: List<Warning>)

private data class CandidateFolder(
    val dir: File,
    val relativePath: String,
    val depth: Int,
    val hasNumberedMdFile: Boolean
)

fun resolveWorkingDir(rootDir: File, subfolder: String?): WorkingDirResolution {
    val warnings = mutableListOf<Warning>()

    // .git and other dot-directories only contain VCS/metadata, never chapter content - skip descending into them
    val allMdFiles = rootDir.walkTopDown()
        .onEnter { dir -> !dir.name.startsWith(".") }
        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .toList()

    if (allMdFiles.isEmpty()) {
        warnings.add(Warning("No markdown files were found in the provided source."))
        return WorkingDirResolution(rootDir, warnings)
    }

    val trimmedSubfolder = subfolder?.trim()?.trim('/', '\\')

    if (!trimmedSubfolder.isNullOrEmpty() && trimmedSubfolder != ".") {
        val rootCanonical = rootDir.canonicalFile
        val candidate = File(rootDir, trimmedSubfolder).canonicalFile

        if (!isWithinDir(rootCanonical, candidate)) {
            warnings.add(
                Warning("Specified subfolder \"$trimmedSubfolder\" is outside the source and was ignored; falling back to automatic folder detection.")
            )
        } else if (!candidate.exists() || !candidate.isDirectory) {
            warnings.add(
                Warning("Specified subfolder \"$trimmedSubfolder\" does not exist; falling back to automatic folder detection.")
            )
        } else {
            warnings.addAll(noMarkdownFilesWarning(candidate, "specified subfolder \"$trimmedSubfolder\""))
            return WorkingDirResolution(candidate, warnings)
        }
    }

    val candidateFolders = findCandidateFolders(rootDir, allMdFiles)

    val sorted = candidateFolders.sortedWith(
        compareByDescending<CandidateFolder> { it.hasNumberedMdFile }
            .thenBy { it.depth }
            .thenBy { it.relativePath }
    )

    val chosen = sorted.first()

    sorted.drop(1).forEach { alternative ->
        val displayPath = alternative.relativePath.ifEmpty { "." }
        warnings.add(
            Warning(
                "Folder \"$displayPath\" also contains markdown files and could be used to generate a book instead. " +
                    "Use the subfolder parameter with \"$displayPath\" to select it."
            )
        )
    }

    return WorkingDirResolution(chosen.dir, warnings)
}

private fun findCandidateFolders(rootDir: File, allMdFiles: List<File>): List<CandidateFolder> {
    return allMdFiles
        .groupBy { it.parentFile }
        .map { (dir, mdFiles) ->
            val relativePath = dir.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            val depth = if (relativePath.isEmpty()) 0 else relativePath.count { it == '/' } + 1

            CandidateFolder(dir, relativePath, depth, containsNumberedMdFile(mdFiles))
        }
}

private fun noMarkdownFilesWarning(dir: File, label: String): List<Warning> {
    val hasMdFile = dir.listFiles()?.any { it.isFile && it.extension.equals("md", ignoreCase = true) } == true
    return if (!hasMdFile) listOf(Warning("No markdown files were found in the $label.")) else emptyList()
}
