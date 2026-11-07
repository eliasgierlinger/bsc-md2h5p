package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.render.H5PHtml
import java.io.File
import java.security.MessageDigest

/** An image file together with the name it gets inside the package, at `content/images/[packedName]`. */
data class PackedImage(val file: File, val packedName: String)

/**
 * Turns the image paths written in Markdown into files on disk plus the name they are packed under.
 *
 * The packed name is the file's own name plus a short hash of its content.
 * Two `logo.png` from different folders stay apart, the same image referenced from
 * two chapters is stored once, the name is stable across runs, and nothing about the folder
 * structure of the author's machine leaks into the package.
 */
class ImageResolver(private val policy: PathPolicy) {
    private val resolved = mutableMapOf<String, PackedImage?>()
    private val _warnings = mutableListOf<Warning>()

    private val downloadDir: File by lazy { WorkDirs.createTempDir("external_images") }
    private val external = ExternalImageFetcher(policy.allowPrivateAddresses) { downloadDir }

    /** Everything that has to go into the package - the same image referenced twice appears once. */
    val images: List<PackedImage>
        get() = resolved.values.filterNotNull().distinctBy { it.packedName }

    val warnings: List<Warning>
        get() = _warnings.toList()

    /** The name [referencedPath] will have under `content/images/`, or null if it can't be packed. */
    fun packedName(referencedPath: String): String? {
        if (!resolved.containsKey(referencedPath)) {
            resolved[referencedPath] = resolve(referencedPath)
        }
        return resolved[referencedPath]?.packedName
    }

    private fun resolve(referencedPath: String): PackedImage? =
        if (isExternalReference(referencedPath)) resolveExternal(referencedPath) else resolveLocal(referencedPath)

    /**
     * An image that is not a file: one on the internet, or one written into the Markdown as a
     * `data:` URL.
     */
    private fun resolveExternal(referencedPath: String): PackedImage? =
        when (val fetch = external.fetch(referencedPath)) {
            is ImageFetch.Failed -> {
                _warnings.add(fetch.warning)
                null
            }

            is ImageFetch.Fetched -> PackedImage(fetch.file, packedNameFor(fetch.file))
        }

    private fun resolveLocal(referencedPath: String): PackedImage? =
        when (val resolution = resolveReferencedPath(referencedPath, policy)) {
            is PathResolution.Rejected -> {
                _warnings.add(resolution.warning)
                null
            }

            is PathResolution.Resolved -> {
                val extension = resolution.file.extension.lowercase()
                if (extension in H5PHtml.ALLOWED_IMAGE_EXTENSIONS) {
                    PackedImage(resolution.file, packedNameFor(resolution.file))
                } else {
                    // A package carrying a file type outside the whitelist is rejected as a whole
                    // ("not-in-whitelist"), so one webp would cost the entire book.
                    val what = if (extension.isEmpty()) "has no file extension" else "is a .$extension file"
                    _warnings.add(
                        Warning(
                            "Image \"$referencedPath\" $what and was skipped: H5P packages only accept " +
                                H5PHtml.ALLOWED_IMAGE_EXTENSIONS.joinToString(", ") { ".$it" } +
                                " (webp and svg are rejected)."
                        )
                    )
                    null
                }
            }
        }
}

private fun isExternalReference(referencedPath: String): Boolean =
    REFERENCE_SCHEME.containsMatchIn(referencedPath)

private val REFERENCE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]+:")

private fun packedNameFor(file: File): String {
    val stem = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifEmpty { "image" }
    val extension = file.extension.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
    return "$stem-${contentHash(file)}" + if (extension.isEmpty()) "" else ".$extension"
}

private fun contentHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return digest.take(12).joinToString("") { "%02x".format(it) }
}

/** Only the extensions in [H5PHtml.ALLOWED_IMAGE_EXTENSIONS] can get this far. */
fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "tif", "tiff" -> "image/tiff"
    else -> "image/png"
}
