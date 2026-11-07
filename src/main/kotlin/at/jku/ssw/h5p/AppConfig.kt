package at.jku.ssw.h5p

import java.io.File
import java.io.InputStream

object AppConfig {
    val outputDir: File by lazy { WorkDirs.childDir("h5p_downloads") }

    /**
     * The bundled H5P template. It is read straight out of the jar every time a package is built and never unpacked
     */
    fun templateZip(): InputStream =
        this::class.java.getResourceAsStream("/h5parchive_template.zip")
            ?: throw IllegalStateException("resource /h5parchive_template.zip not found!")
}
