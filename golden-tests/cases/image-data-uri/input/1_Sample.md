# Sample

A one-pixel PNG written into the Markdown: ![Red dot](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==)

The same picture again, which has to share the first one's entry: ![Red dot again](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==)

A GIF that is percent-encoded rather than base64: ![Dot](data:image/gif,GIF89a%01%00%01%00%80%00%00%00%00%00%FF%FF%FF%21%F9%04%01%00%00%00%00%2C%00%00%00%00%01%00%01%00%00%02%01D%00%3B)

A type H5P does not accept: ![Webp](data:image/webp;base64,UklGRhIAAABXRUJQVlA4TAYAAAAvAAAAAA==)

Not an image at all: ![Text](data:text/plain;base64,aGVsbG8=)

Base64 that does not decode: ![Broken](data:image/png;base64,!!!!)

Nothing after the comma: ![Empty](data:image/png;base64,)

Text continues normally after all of them.
