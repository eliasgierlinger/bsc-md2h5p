package at.jku.ssw.h5p.cli

import at.jku.ssw.h5p.converter.InputSource
import at.jku.ssw.h5p.converter.convert
import at.jku.ssw.h5p.input.GitRepoSource
import at.jku.ssw.h5p.merge.mergePackages
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun runCli(args: List<String>): Boolean {
    val options = parseOptions(args)
    val folder = options["folder"]
    val repo = options["repo"]
    val subfolder = options["subfolder"]

    if ((folder == null) == (repo == null)) {
        println("Error: specify exactly one of --folder <path> or --repo <url>")
        return false
    }

    val explicitTitle = options["title"]?.trim()?.ifBlank { null }

    val (sourceDir, inputSource, title) = if (folder != null) {
        val dir = File(folder)
        if (!dir.exists() || !dir.isDirectory) {
            println("Error: folder not found: ${dir.absolutePath}")
            return false
        }
        Triple(dir, InputSource.LOCAL_FOLDER, explicitTitle ?: dir.name)
    } else {
        println("Cloning repository: $repo")
        val dir = GitRepoSource.clone(repo!!)
        Triple(dir, InputSource.GIT_REPO, explicitTitle ?: GitRepoSource.repoNameFromUrl(repo))
    }

    val outputFile = options["output"]?.let { path ->
        val file = File(path).absoluteFile
        if (file.isDirectory) {
            println("Error: --output names the file to write, but \"${file.path}\" is a folder")
            return false
        }
        if (file.parentFile?.isDirectory == false) {
            println("Error: folder not found: ${file.parentFile.path}")
            return false
        }
        file
    }

    val mergeWith = options["merge-with"]?.let { path ->
        val file = File(path)
        if (!file.isFile) {
            println("Error: package not found: ${file.absolutePath}")
            return false
        }
        file
    }

    println("Converting \"$title\"...")
    val converted = convert(sourceDir, inputSource, title, subfolder)

    val result = if (mergeWith == null) {
        converted
    } else {
        println("Merging with ${mergeWith.name}...")
        val merged = mergePackages(converted.file, mergeWith)
        merged.copy(warnings = converted.warnings + merged.warnings)
    }

    if (result.warnings.isNotEmpty()) {
        println("Warnings:")
        result.warnings.forEach { println("- ${it.message}") }
    }

    println("Done: ${deliver(result.file, outputFile).path}")
    return true
}

/**
 * Moves the finished package to where the person who ran the command can find it: the file they
 * named, or one beside them in the working directory.
 */
private fun deliver(packaged: File, target: File?): File {
    val destination = target ?: File(packaged.name).absoluteFile
    Files.move(packaged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    packaged.parentFile?.delete()
    return destination
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--") && i + 1 < args.size) {
            options[arg.removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            i += 1
        }
    }
    return options
}
