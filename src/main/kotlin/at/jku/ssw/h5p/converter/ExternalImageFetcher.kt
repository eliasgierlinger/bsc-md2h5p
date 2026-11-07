package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.render.H5PHtml
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

/** An image that has been made into a file, or the reason it could not be. */
sealed interface ImageFetch {
    data class Fetched(val file: File) : ImageFetch
    data class Failed(val warning: Warning) : ImageFetch
}

/**
 * Makes a file out of an image that is not one: a URL to fetch, or a `data:` URL carrying the bytes
 * itself.
 *
 * A network fetch is bounded in three directions, because the URL is written by whoever wrote the
 * Markdown and the web server converts repositories uploaded by strangers:
 *
 * - **Time**: connect and read timeouts, so an unresponsive host delays one image, not the book.
 * - **Size**: [MAX_BYTES], enforced while reading rather than by trusting `Content-Length`.
 * - **Where it may point**: unless [allowPrivateAddresses], an address that is loopback,
 *   link-local, site-local or otherwise not a public one is refused. Without that, a Markdown file
 *   could make the *server* fetch `http://127.0.0.1:8080/…` or a cloud metadata endpoint and pack
 *   the answer into a book anybody can download.
 */
class ExternalImageFetcher(
    private val allowPrivateAddresses: Boolean,
    private val targetDir: () -> File
) {
    private var downloadCount = 0

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun fetch(reference: String): ImageFetch {
        if (reference.startsWith(DATA_SCHEME, ignoreCase = true)) return decodeDataUrl(reference)

        val uri = runCatching { URI(reference) }.getOrNull()
            ?: return failed(reference, "is not a valid URL")

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return failed(reference, "uses the \"$scheme\" scheme, of which only http, https and data are supported")
        }

        return download(reference, uri, redirectsLeft = MAX_REDIRECTS)
    }

    /**
     * `data:image/png;base64,iVBOR…` - the image is in the Markdown itself
     */
    private fun decodeDataUrl(reference: String): ImageFetch {
        val specification = reference.substring(DATA_SCHEME.length)
        val comma = specification.indexOf(',')
        if (comma < 0) return failed(reference, "is not a valid data URL: nothing separates the type from the data")

        val parameters = specification.take(comma).split(';').map { it.trim().lowercase() }
        val mediaType = parameters.firstOrNull().orEmpty().ifEmpty { DATA_DEFAULT_TYPE }
        if (!mediaType.startsWith("image/")) {
            return failed(reference, "is a data URL of type \"$mediaType\", which is not an image")
        }

        val extension = extensionForType(mediaType) ?: return failed(reference, unsupportedTypeReason())

        val payload = specification.substring(comma + 1)
        val bytes = if (parameters.contains("base64")) {
            // The MIME decoder rather than the plain one: a data URL written by hand or wrapped by an
            // editor can carry line breaks, and the strict decoder refuses those.
            runCatching { Base64.getMimeDecoder().decode(payload) }.getOrElse {
                return failed(reference, "is a data URL whose base64 data could not be decoded")
            }
        } else {
            // Percent-encoded bytes, one per character - which is what ISO-8859-1 gives back.
            runCatching { URLDecoder.decode(payload, StandardCharsets.ISO_8859_1) }
                .getOrElse { return failed(reference, "is a data URL whose data could not be decoded") }
                .toByteArray(StandardCharsets.ISO_8859_1)
        }

        if (bytes.isEmpty()) {
            val reason = if (payload.isBlank()) {
                "is a data URL with no data in it"
            } else {
                "is a data URL whose data could not be decoded"
            }
            return failed(reference, reason)
        }
        if (bytes.size > MAX_BYTES) return failed(reference, tooLargeReason())

        return ImageFetch.Fetched(write("image", extension, bytes))
    }

    private fun download(reference: String, uri: URI, redirectsLeft: Int): ImageFetch {
        rejectionFor(uri)?.let { return failed(reference, it) }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            .getOrElse { failure ->
                // Host unknown, connection refused, TLS refused, timed out: all the same to the author,
                // who wants to know which image is missing and roughly why.
                val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName
                return failed(reference, "could not be fetched ($reason)")
            }

        response.body().use { body ->
            if (response.statusCode() in REDIRECT_CODES) {
                val location = response.headers().firstValue("location").orElse(null)
                    ?: return failed(reference, "was redirected without a target (HTTP ${response.statusCode()})")
                if (redirectsLeft == 0) return failed(reference, "is redirected too many times")
                val next = runCatching { uri.resolve(location) }.getOrNull()
                    ?: return failed(reference, "was redirected to \"$location\", which is not a valid URL")
                return download(reference, next, redirectsLeft - 1)
            }

            if (response.statusCode() !in 200..299) {
                return failed(reference, "could not be fetched: the server answered ${response.statusCode()}")
            }

            val contentType = response.headers().firstValue("content-type").getOrNull()?.substringBefore(';')?.trim()?.lowercase() ?: ""
            if (contentType.isNotEmpty() && !contentType.startsWith("image/")) {
                // Typically an error page or a login form served with status 200. Packing it would
                // produce a .png that is HTML, which H5P shows as a broken image and nothing else.
                return failed(reference, "is not an image: the server answered with \"$contentType\"")
            }

            val extension = extensionFor(uri, contentType) ?: return failed(reference, unsupportedTypeReason())

            val bytes = readAtMost(body, MAX_BYTES) ?: return failed(reference, tooLargeReason())

            return ImageFetch.Fetched(write(stemOf(uri), extension, bytes))
        }
    }

    /**
     * Files land one per directory so that each can keep the name its URL gave it: the packed name is
     * built from that name plus a hash of the content, and a random suffix in it would make the same
     * image pack under a different name on every run.
     */
    private fun write(stem: String, extension: String, bytes: ByteArray): File {
        val dir = File(targetDir(), (downloadCount++).toString())
        dir.mkdirs()
        val file = File(dir, "$stem.$extension")
        file.writeBytes(bytes)
        return file
    }

    /** Why [uri] may not be requested, or null if it may. */
    private fun rejectionFor(uri: URI): String? {
        val host = uri.host ?: return "has no host name"
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: return "could not be fetched: the host \"$host\" is unknown"
        return if (!allowPrivateAddresses && addresses.any { !it.isPublic() }) {
            "points at \"$host\", which is a local or private address, and was skipped"
        } else {
            null
        }
    }

    private fun unsupportedTypeReason(): String =
        "has no file type H5P accepts: only " +
            H5PHtml.ALLOWED_IMAGE_EXTENSIONS.joinToString(", ") { ".$it" } +
            " are packed (webp and svg are rejected)"

    private fun tooLargeReason(): String = "is larger than ${MAX_BYTES / 1024 / 1024} MB and was skipped"

    /**
     * A data URL is the whole image, so it can be tens of thousands of characters long. Printing one
     * in full would bury every other warning, and in the golden tests it would put a base64 blob into
     * a file meant to be read in a diff.
     */
    private fun failed(reference: String, reason: String): ImageFetch.Failed {
        val shown = if (reference.length <= MAX_SHOWN) reference else reference.take(MAX_SHOWN) + "..."
        return ImageFetch.Failed(Warning("Image \"$shown\" $reason."))
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 30L
        const val USER_AGENT = "md2h5p"
        const val MAX_SHOWN = 60
        const val DATA_SCHEME = "data:"

        /** What a data URL means when it names no type at all, per RFC 2397. */
        const val DATA_DEFAULT_TYPE = "text/plain"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun InetAddress.isPublic(): Boolean =
    !isLoopbackAddress && !isAnyLocalAddress && !isLinkLocalAddress &&
        !isSiteLocalAddress && !isMulticastAddress && !isUniqueLocal()

/**
 * `fc00::/7`, the IPv6 counterpart of the private IPv4 ranges. [InetAddress.isSiteLocalAddress]
 * covers only the deprecated `fec0::/10`, so without this a private IPv6 address would pass.
 */
private fun InetAddress.isUniqueLocal(): Boolean =
    address.size == 16 && (address[0].toInt() and 0xfe) == 0xfc

private fun fileNameOf(uri: URI): String = (uri.path ?: "").substringAfterLast('/')

private fun stemOf(uri: URI): String {
    val name = fileNameOf(uri)
    return name.substringBeforeLast('.', name)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(40)
        .ifEmpty { "image" }
}

/**
 * The extension the fetched file gets. The URL decides when it names a type H5P accepts; otherwise
 * the server's `Content-Type` does, since plenty of image URLs are a path with no extension at all.
 */
private fun extensionFor(uri: URI, contentType: String): String? {
    val fromUrl = fileNameOf(uri).substringAfterLast('.', "").lowercase()
    if (fromUrl in H5PHtml.ALLOWED_IMAGE_EXTENSIONS) return fromUrl
    return extensionForType(contentType)
}

private fun extensionForType(contentType: String): String? = when (contentType) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/bmp", "image/x-ms-bmp" -> "bmp"
    "image/tiff" -> "tiff"
    else -> null
}

/** The bytes of [stream], or null if there are more than [limit] of them. */
private fun readAtMost(stream: InputStream, limit: Long): ByteArray? {
    val bytes = stream.readNBytes((limit + 1).toInt())
    return if (bytes.size > limit) null else bytes
}
