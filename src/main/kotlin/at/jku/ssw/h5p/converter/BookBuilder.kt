package at.jku.ssw.h5p.converter

import at.jku.ssw.h5p.converter.json.H5BookCover
import at.jku.ssw.h5p.converter.json.H5PBehaviour
import at.jku.ssw.h5p.converter.json.H5PBook
import at.jku.ssw.h5p.converter.json.H5PChapter

class BookBuilder {
    private val chapters = mutableListOf<H5PChapter>()
    private var showCoverPage: Boolean = false
    private var coverDescription: String = ""
    private var baseColor: String = "#1768c4"
    private var defaultTableOfContents: Boolean = true

    fun setShowCoverPage(show: Boolean) = apply {
        this.showCoverPage = show
    }

    fun setCoverDescription(description: String) = apply {
        this.coverDescription = description
    }

    fun setBaseColor(colorHex: String) = apply {
        this.baseColor = colorHex
    }

    fun setDefaultTableOfContents(show: Boolean) = apply {
        this.defaultTableOfContents = show
    }

    fun addPage(chapter: H5PChapter) = apply {
        this.chapters.add(chapter)
    }

    fun addPage(title: String, block: PageBuilder.() -> Unit) = apply {
        val pageBuilder = PageBuilder(title)
        pageBuilder.block()
        this.chapters.add(pageBuilder.build())
    }

    fun build(): H5PBook {
        return H5PBook(
            showCoverPage = this.showCoverPage,
            bookCover = H5BookCover(coverDescription = this.coverDescription),
            chapters = this.chapters.toList(),
            behaviour = H5PBehaviour(
                baseColor = this.baseColor,
                defaultTableOfContents = this.defaultTableOfContents
            )
        )
    }
}