package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.converter.ImageResolver
import at.jku.ssw.h5p.converter.imageElement
import at.jku.ssw.h5p.converter.mimeTypeOf
import org.commonmark.ext.image.attributes.ImageAttributes
import org.commonmark.node.Image

/**
 * Turns an image in the Markdown into the content block that will represent it.
 *
 * Deliberately knows nothing about where that block ends up: `img` is allowed in no H5P text field,
 * so an image is *always* a block, and which enclosing construct has to make room for it is the
 * construct's own business - see [RenderContext].
 */
class ImageRenderer(
    private val images: ImageResolver,
    private val warnings: MutableCollection<Warning>
) {

    /** The block for [image], or null if it has no destination or the file cannot be packed. */
    fun blockFor(image: Image): PlacedBlock? {
        val destination = image.destination ?: ""
        if (destination.isEmpty()) {
            warnings.add(Warning("Image with missing destination was ignored."))
            return null
        }

        // The resolver warns for itself about paths it rejects and file types H5P will not take.
        val packedName = images.packedName(destination) ?: return null

        val attributes = attributesOf(image)

        // Alt text is the image's bracket-text content (e.g. "cat" in ![cat](url)), not its title
        // attribute - those are two different things (title is typically shown as a hover tooltip).
        val altText = plainTextOf(image).ifEmpty { "Image" }

        return PlacedBlock(
            element = imageElement(
                imagePath = "images/$packedName",
                // H5P.Image ignores width and height and renders at full width either way, so they are metadata
                // and nothing more
                width = attributes["width"]?.toIntOrNull() ?: 1920,
                height = attributes["height"]?.toIntOrNull() ?: 1080,
                altText = altText,
                hoverTitle = image.title ?: altText,
                metadataTitle = altText,
                mimeType = mimeTypeOf(packedName)
            ),
            placeholder = "🖼 $altText",
            source = destination
        )
    }

    /** The `{width=…}` attributes of the image-attributes extension, which hang under the node. */
    private fun attributesOf(image: Image): Map<String, String> =
        childrenOfType<ImageAttributes>(image).firstOrNull()?.attributes ?: emptyMap()
}
