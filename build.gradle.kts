import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "9.2.2"
}

group = "at.jku.ssw.h5p"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"

dependencies {
    implementation("org.commonmark:commonmark:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.29.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-alerts:0.29.0")
    implementation("org.commonmark:commonmark-ext-footnotes:0.29.0")
    implementation("org.commonmark:commonmark-ext-heading-anchor:0.29.0")
    implementation("org.commonmark:commonmark-ext-image-attributes:0.29.0")
    implementation("org.commonmark:commonmark-ext-ins:0.29.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.29.0")
    implementation("org.commonmark:commonmark-ext-yaml-front-matter:0.29.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("com.fifesoft:rsyntaxtextarea:3.6.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
}

java {
   toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// The H5P template is kept unpacked under src/main/resources/h5parchive_template so that a library
// change shows up as a reviewable diff, but AppConfig extracts it from a zip at runtime. Zipping it
// during the build keeps the two from drifting apart - the zip used to be a hand-made file that
// *.gitignore excluded, so a fresh clone had no template at all.
val templateZip by tasks.registering(Zip::class) {
    from(layout.projectDirectory.dir("src/main/resources/h5parchive_template"))
    archiveFileName.set("h5parchive_template.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated-resources"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

sourceSets.main {
    // Only the generated zip goes into the jar, not the unpacked copy it is built from.
    resources.exclude("h5parchive_template/**", "h5parchive_template.zip")
}

tasks.processResources {
    from(templateZip)
}

application {
    mainClass.set("at.jku.ssw.h5p.MainKt")
}

// `./gradlew shadowJar` builds the single runnable jar the README documents: everything the program
// needs on one file, so it can be handed to somebody who has a JRE and nothing else
tasks.shadowJar {
    archiveFileName.set("md2h5p.jar")
}
