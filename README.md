# md2h5p

Convert a folder of Markdown files into an H5P Interactive Book (`.h5p`) for Moodle. One file becomes
one chapter. Anything the target format cannot represent is degraded in a defined way and reported as
a warning - a conversion always produces a book.

## Running it with Gradle

```sh
./gradlew run --args="serve"                                  # web UI on http://localhost:8080
./gradlew run --args="convert --folder <path>"
./gradlew run --args="convert --repo <url> [--subfolder <path>]"
```

The book is titled after the folder or repository, and the package is written to the folder the
command was run from, named after the book. Add `--title <text>` to title the book yourself,
`--output <file.h5p>` to name the file yourself, and `--merge-with <package.h5p>` to fold the
conversion into a package that has since been edited in H5P.

## Running it from a jar

`shadowJar` builds one runnable jar holding the program and everything it depends on:

```sh
./gradlew shadowJar            # writes build/libs/md2h5p.jar
```

That file is all that has to be copied to another machine; it needs a Java 25 runtime and nothing
else. It takes the same arguments as the commands above:

```sh
java -jar md2h5p.jar serve                                  # web UI on http://localhost:8080
java -jar md2h5p.jar convert --folder <path>
java -jar md2h5p.jar convert --repo <url> [--subfolder <path>]
```

## Running it with Docker

```sh
docker build -t md2h5p .
```

### Web server (the default)

```sh
docker run --rm -p 8080:8080 md2h5p
```

Then open <http://localhost:8080>. Nothing needs to be mounted: the files are uploaded through the
browser or cloned by the server, and the finished package is downloaded through the same page.
