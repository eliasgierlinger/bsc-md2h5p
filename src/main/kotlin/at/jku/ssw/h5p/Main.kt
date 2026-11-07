package at.jku.ssw.h5p

import at.jku.ssw.h5p.cli.runCli
import at.jku.ssw.h5p.golden.runGoldenTests
import at.jku.ssw.h5p.web.runWeb

fun main(args: Array<String>) {
    if (args.firstOrNull() == "serve") {
        runWeb()
    } else if (args.firstOrNull() == "convert"){
        if (!runCli(args.drop(1))) {
            printUsage()
        }
    } else if (args.firstOrNull() == "golden") {
        runGoldenTests()
    } else {
        printUsage()
    }
}

private fun printUsage() {
    println(
        """
        Usage:
          md2h5p convert --folder <path> [--title <text>] [--output <file.h5p>] [--merge-with <package.h5p>]
          md2h5p convert --repo <url> [--subfolder <path>] [--title <text>] [--output <file.h5p>] [--merge-with <package.h5p>]

        The book is titled after the folder or repository unless --title says otherwise, and the
        package is written to the current folder unless --output names a file.
          md2h5p serve
          md2h5p golden
        """.trimIndent()
    )
}