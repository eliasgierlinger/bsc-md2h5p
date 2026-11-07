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

### CLI

The container has its own file system, so a conversion can only read what has been mounted into it,
and can only hand back a package it writes into a mount. Both directions go through the same volume:

```sh
docker run --rm -v "$PWD:/data" md2h5p convert --folder /data/notes
```

`/data` is the working directory inside the image, which is where the package is written — so it
appears in the mounted folder on the host with nothing further to say. Relative paths work too
(`--folder notes`). Converting a public repository needs no mount for the *input*, only for the
result:

```sh
docker run --rm -v "$PWD:/data" md2h5p convert --repo https://github.com/user/notes
```

Updating a package that was edited in H5P — both the package and the result live in the mount:

```sh
docker run --rm -v "$PWD:/data" md2h5p convert --folder /data/notes --merge-with /data/book.h5p
```

Mount something, or the package is written inside the container and disappears with it. Use
`--output <file.h5p>` to write it somewhere other than the working directory, or under another
name.

### Notes

- The image runs as a non-root user (uid 10001), because the server processes repositories and
  uploads written by somebody other than the operator. On Linux a bind mount keeps its host
  ownership, so if writing to `/data` is refused, add `--user "$(id -u):$(id -g)"`. Docker Desktop on
  Windows and macOS handles this itself.
- Every scratch directory (clones, upload staging, packages on their way out) lives under the
  system temp folder, which is `/tmp` in the container — see `WorkDirs.kt`. 
- Any argument list replaces the default `serve`, which is why the same image serves both modes.

## Development

See `CLAUDE.md` for the architecture, `src/main/resources/Notes.md` for the design log and the
platform research behind it, and the golden tests:

```sh
./gradlew run --args="golden"      # writes into golden-tests/cases/*/output; `git diff` is the result
```
