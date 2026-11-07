package at.jku.ssw.h5p.golden

import at.jku.ssw.h5p.Uuids
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.InputSource
import at.jku.ssw.h5p.converter.convert
import at.jku.ssw.h5p.merge.mergePackages
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val json = Json { prettyPrint = true }

/**
 * Dev-only harness: runs every golden-tests/cases/<name>/ through convert() and (over)writes the
 * interesting parts of the resulting .h5p into that case's output/ folder. Those files are
 * committed, so `git diff` after a run is the actual test result.
 */
fun runGoldenTests() {
    val caseDirs = File("golden-tests/cases").listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
    for (caseDir in caseDirs) {
        runCase(caseDir)
        println("wrote ${caseDir.name}/output")
    }
}

private fun runCase(caseDir: File) {
    val config = json.decodeFromString<GoldenCaseConfig>(File(caseDir, "case.json").readText())
    val (rootDir, inputSource) = prepareInput(config.mode, File(caseDir, "input"))

    // repeatable subContentIds, otherwise every run would show up as a diff
    Uuids.startFixedSequence()
    val converted = convert(rootDir, inputSource, config.title, config.subfolder)

    // A merge case converts the same way any other does and only then folds in the edited package -
    // the point of D26 being that merging is post-processing, not a second pipeline.
    val result = config.mergeWith?.let { fixture ->
        val merged = mergePackages(converted.file, zipFixture(File(caseDir, fixture)))
        merged.copy(warnings = converted.warnings + merged.warnings)
    } ?: converted

    val outputDir = File(caseDir, "output")
    outputDir.deleteRecursively()
    outputDir.mkdirs()
    File(outputDir, "warnings.txt").writeText(result.warnings.map { it.message }.sorted().joinToString("\n"))

    result.file.copyTo(File(outputDir, "${caseDir.name}.h5p"), overwrite = true)

    ZipFile(result.file).use { zip ->
        zip.entries().asSequence()
            .filter { candidate: ZipEntry ->
                candidate.name == "h5p.json" || candidate.name == "content/content.json" ||
                    candidate.name.startsWith("content/images/")
            }
            .forEach { entry ->
                val outFile = File(outputDir, entry.name)
                outFile.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
            }
    }

    prettyPrintInPlace(File(outputDir, "h5p.json"))
    prettyPrintInPlace(File(outputDir, "content/content.json"))
}

/**
 * A clone and an upload both leave convert() with a scratch copy of the input; only the
 * InputSource (and with it the path policy) differs, so the golden runner stages both the same
 * way instead of really cloning. A local folder is handed over as-is, like the CLI does.
 */
private fun prepareInput(mode: String, inputDir: File): Pair<File, InputSource> = when (mode) {
    "local" -> inputDir to InputSource.LOCAL_FOLDER
    "git" -> stagedCopy(inputDir, "golden-git-") to InputSource.GIT_REPO
    "upload" -> stagedCopy(inputDir, "golden-upload-") to InputSource.UPLOADED_FILES
    else -> error("unknown mode \"$mode\" (expected local, git, or upload)")
}

private fun stagedCopy(inputDir: File, prefix: String): File =
    WorkDirs.createTempDir(prefix).also { inputDir.copyRecursively(it, overwrite = true) }

/**
 * Zips an unpacked "already edited in H5P" fixture into a real `.h5p`. Stored unpacked in the case so
 * that a change to it is a reviewable diff rather than a binary blob - the same reason the H5P
 * template lives unpacked under `src/main/resources`.
 */
private fun zipFixture(fixtureDir: File): File {
    val target = File(WorkDirs.createTempDir("golden-merge-"), "${fixtureDir.parentFile.name}-edited.h5p")
    ZipOutputStream(target.outputStream()).use { zos ->
        fixtureDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            zos.putNextEntry(ZipEntry(file.relativeTo(fixtureDir).path.replace("\\", "/")))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
    return target
}

private fun prettyPrintInPlace(file: File) {
    file.writeText(json.encodeToString(Json.parseToJsonElement(file.readText())))
}
