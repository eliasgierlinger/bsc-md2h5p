package at.jku.ssw.h5p

import java.io.File
import java.nio.file.Files

object WorkDirs {
    private val rootDir: File = File(System.getProperty("java.io.tmpdir"), "md2h5p")

    val sourcesDir: File by lazy { childDir("h5p_sources") }

    fun childDir(name: String): File {
        val dir = File(rootDir, name)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createTempDir(prefix: String): File {
        return Files.createTempDirectory(sourcesDir.toPath(), prefix).toFile()
    }

    fun createOutputDir(): File {
        discardExpiredOutputs()
        return Files.createTempDirectory(AppConfig.outputDir.toPath(), "package-").toFile()
    }

    /**
     * Deletes the packages that have been sitting in the output directory for longer than
     * [OUTPUT_LIFETIME_MILLIS].
     */
    private fun discardExpiredOutputs() {
        val expiredBefore = System.currentTimeMillis() - OUTPUT_LIFETIME_MILLIS
        AppConfig.outputDir.listFiles()
            ?.filter { it.isDirectory && it.lastModified() < expiredBefore }
            ?.forEach(::discard)
    }

    /**
     * Deletes a scratch directory once nothing needs it anymore.
     */
    fun discard(dir: File) {
        repeat(DELETE_ATTEMPTS) {
            if (dir.deleteRecursively() || !dir.exists()) return
            Thread.sleep(DELETE_RETRY_MILLIS)
        }
        System.err.println("Could not delete the scratch directory ${dir.path}; it is left behind.")
    }

    /** How long a finished package stays downloadable. */
    private const val OUTPUT_LIFETIME_MILLIS = 15L * 60 * 1000

    private const val DELETE_ATTEMPTS = 10
    private const val DELETE_RETRY_MILLIS = 100L
}
