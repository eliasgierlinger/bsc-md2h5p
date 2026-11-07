package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import java.io.File

enum class InputSource { LOCAL_FOLDER, GIT_REPO, UPLOADED_FILES }

/**
 * What a referenced file or URL is judged against.
 *
 * [workingDir] is what referenced paths resolve against; [restrictToDir], when set, is the folder
 * they are not allowed to leave; [allowPrivateAddresses] says whether an image URL may point into
 * the network the converter itself is on.
 */
data class PathPolicy(
    val workingDir: File,
    val restrictToDir: File?,
    val allowPrivateAddresses: Boolean
) {
    companion object {
        fun forSource(source: InputSource, rootDir: File, workingDir: File): PathPolicy = when (source) {
            InputSource.LOCAL_FOLDER ->
                PathPolicy(workingDir, restrictToDir = null, allowPrivateAddresses = true)

            InputSource.GIT_REPO, InputSource.UPLOADED_FILES ->
                PathPolicy(workingDir, restrictToDir = rootDir, allowPrivateAddresses = false)
        }
    }
}

sealed interface PathResolution {
    data class Resolved(val file: File) : PathResolution
    data class Rejected(val warning: Warning) : PathResolution
}

/** Resolves one path referenced from markdown against [policy], or says why it can't be used. */
fun resolveReferencedPath(referencedPath: String, policy: PathPolicy): PathResolution {
    val notFound = PathResolution.Rejected(
        Warning("Referenced path \"$referencedPath\" could not be found and was skipped.")
    )

    val candidate = resolveCandidate(policy.workingDir, referencedPath) ?: return notFound

    val restrictToCanonical = policy.restrictToDir?.canonicalFile
    if (restrictToCanonical != null && !isWithinDir(restrictToCanonical, candidate)) {
        return PathResolution.Rejected(
            Warning("Referenced path \"$referencedPath\" is outside the allowed folder and was skipped.")
        )
    }

    return PathResolution.Resolved(candidate)
}

/**
 * A referenced path is normally relative to the chapter folder, but a local markdown file may also
 * point at an image by absolute path - which glued onto the working dir gives nonsense ("C:\book\
 * C:\pic.png", which Windows refuses to canonicalize at all).
 */
private fun resolveCandidate(workingDir: File, referencedPath: String): File? {
    val relative = canonicalOrNull(File(workingDir, referencedPath))
    if (relative != null && relative.isFile) return relative

    val absolute = File(referencedPath).takeIf { it.isAbsolute }?.let { canonicalOrNull(it) }?.takeIf { it.isFile }
    return absolute
}

private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

fun isWithinDir(baseCanonical: File, candidate: File): Boolean {
    if (candidate == baseCanonical) return true
    val basePrefix = baseCanonical.path.removeSuffix(File.separator) + File.separator
    return candidate.path.startsWith(basePrefix)
}
