package at.jku.ssw.h5p.input

import at.jku.ssw.h5p.WorkDirs
import org.eclipse.jgit.api.Git
import java.io.File

object GitRepoSource {
    fun clone(repoUrl: String): File {
        val targetDir = WorkDirs.createTempDir("md2h5p-repo-")

        try {
            Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir)
                .setDepth(1)
                .call()
                .close()
        } catch (e: Throwable) {
            WorkDirs.discard(targetDir)
            throw e
        }

        return targetDir
    }

    fun repoNameFromUrl(repoUrl: String): String {
        val trimmed = repoUrl.trimEnd('/').removeSuffix(".git")
        return trimmed.substringAfterLast('/').ifEmpty { "book" }
    }
}
