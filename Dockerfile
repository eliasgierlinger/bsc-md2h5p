# md2h5p in a container.
#
# Two modes out of one image. `serve` is the default, so `docker run -p 8080:8080 md2h5p` starts the
# web server and nothing else has to be said; any other argument list replaces it, which is what makes
# `docker run md2h5p convert --folder ...` work without a second image or an entrypoint script.
#
#   docker build -t md2h5p .
#   docker run --rm -p 8080:8080 md2h5p
#   docker run --rm -v "$PWD:/data" md2h5p convert --folder /data/notes --output /data
#
# See README.md for what the volume is for and how to get the package back out.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# The build files first, on their own: this layer is what caches the Gradle distribution and the
# dependency downloads, and it only has to be redone when a dependency actually changes. Copying the
# sources in first would throw both away on every edit.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# The repository is checked out on Windows with core.autocrlf=true, so gradlew arrives with CRLF line
# endings and the kernel reads "#!/bin/sh\r" as the interpreter name. Stripping them here keeps the
# image buildable from a Windows working copy without committing a .gitattributes.
RUN sed -i 's/\r$//' gradlew \
 && chmod +x gradlew \
 && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# The server accepts uploads and clones repositories written by somebody who is not the operator
# (see PathSecurity), so it does not run as root. On Linux a bind mount keeps its host ownership, so
# writing into one may need --user "$(id -u):$(id -g)"; Docker Desktop handles this itself.
RUN useradd --create-home --uid 10001 md2h5p \
 && mkdir -p /data \
 && chown md2h5p:md2h5p /data

COPY --from=build /src/build/install/md2h5p /opt/md2h5p

USER md2h5p

# Where a bind mount is expected to land, and the working directory a relative --folder resolves
# against, so `convert --folder notes` reads the mount rather than something inside the image.
WORKDIR /data

EXPOSE 8080

ENTRYPOINT ["/opt/md2h5p/bin/md2h5p"]
CMD ["serve"]
