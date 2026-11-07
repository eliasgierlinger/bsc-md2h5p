package at.jku.ssw.h5p.converter.json

import kotlinx.serialization.Serializable

/**
 * Corresponds to h5p.json in H5P archive
 */
@Serializable
data class H5PInfo(
    val title: String,
    val extraTitle: String = title,

    val language: String = "en",
    val defaultLanguage: String = "en",
    val embedTypes: List<String> = listOf("iframe"),
    val license: String = "U",
    val mainLibrary: String = "H5P.InteractiveBook",
    val preloadedDependencies: List<H5PDependency> = DefaultDependencies.interactiveBook()
)

@Serializable
data class H5PDependency(
    val machineName: String,
    val majorVersion: Int,
    val minorVersion: Int
)

object DefaultDependencies {
    fun interactiveBook(): List<H5PDependency> = listOf(
        H5PDependency("H5P.AdvancedText", 1, 1),
        H5PDependency("H5P.Image", 1, 1),
        H5PDependency("H5P.Table", 1, 2),
        H5PDependency("H5P.Column", 1, 22),
        H5PDependency("H5P.Row", 1, 3),
        H5PDependency("H5P.RowColumn", 1, 3),
        H5PDependency("FontAwesome", 4, 5),
        H5PDependency("H5P.JoubelUI", 1, 3),
        H5PDependency("H5P.Transition", 1, 0),
        H5PDependency("H5P.FontIcons", 1, 0),
        H5PDependency("H5P.InteractiveBook", 1, 15),
        // Pulled in by InteractiveBook 1.15 and Image 1.1 since their redesign; jQuery.ui is
        // H5P.Components' own dependency.
        H5PDependency("H5P.Components", 1, 0),
        H5PDependency("jQuery.ui", 1, 10),
        // The quiz libraries a custom fenced block can produce. Their versions are the ones
        // H5P.Column 1.22 names in its semantics, and no other version is allowed there.
        H5PDependency("H5P.Question", 1, 5),
        H5PDependency("H5P.MultiChoice", 1, 16),
        H5PDependency("H5P.SingleChoiceSet", 1, 11)
    )
}