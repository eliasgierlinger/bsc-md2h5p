package at.jku.ssw.h5p.converter.render

import at.jku.ssw.h5p.Warning
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/**
 * Code blocks, colored while converting rather than while displaying.
 *
 * The tokenizer is RSyntaxTextArea's. It is nominally a Swing editor component, but [RSyntaxDocument]
 * is a plain `javax.swing.text.Document` - tokenizing constructs nothing graphical and needs no
 * display. Using the document rather than a bare `TokenMaker` is what carries the state of a
 * multi-line comment or string from one line to the next.
 */
class CodeHighlighter(private val warnings: MutableSet<Warning>) {

    /**
     * Renders one code block, whatever came of it. [info] is the fence's info string, or null for an
     * indented block - which has no way to name a language and therefore gets the box but no color.
     */
    fun render(literal: String, info: String?): String {
        val declared = info?.trim()?.takeIf { it.isNotEmpty() }

        // The info string may carry more than a language (```kotlin title=Build.kt), and only the
        // first word is ever the language.
        val language = declared?.substringBefore(' ')?.lowercase()
        val syntaxStyle = language?.let { SYNTAX_STYLES[it] }

        if (language != null && syntaxStyle == null) {
            // One warning per language rather than per block: `warnings` is a set of a data class, so
            // a chapter using the same unknown language ten times still says it once.
            warnings.add(
                Warning(
                    "Code block language \"$language\" is not known to the syntax highlighter. " +
                        "The block is rendered without highlighting."
                )
            )
        }

        val body = if (syntaxStyle == null || syntaxStyle == SyntaxConstants.SYNTAX_STYLE_NONE) {
            escapeHtml(literal)
        } else {
            highlight(literal, syntaxStyle)
        }

        val classAttr = if (language != null) " class=\"language-${escapeHtml(language)}\"" else ""
        return "<div style=\"${H5PHtml.CODE_BLOCK_STYLE}\">\n" +
            "<pre><code$classAttr>$body</code></pre>\n" +
            "</div>\n"
    }

    /** Escaped code with a `span` around every run that has a color. */
    private fun highlight(literal: String, syntaxStyle: String): String {
        val document = RSyntaxDocument(syntaxStyle)
        val spans = SpanWriter()

        try {
            document.insertString(0, literal, null)

            val lines = document.defaultRootElement.elementCount
            for (line in 0 until lines) {
                // `getTokenListForLine` stops at the line end, so the newline between two lines is
                // ours to write back. Writing it without touching the open span is what lets a
                // multi-line comment stay one span instead of one per line.
                if (line > 0) spans.appendInsideSpan("\n")

                var token = document.getTokenListForLine(line)
                while (token != null && token.type != TokenTypes.NULL) {
                    val text = token.lexeme ?: break
                    spans.append(COLORS[token.type], text)
                    token = token.nextToken
                }
            }
        } catch (e: Exception) {
            // Warn and skip, never fail (D3): a tokenizer that trips over an input is not a reason to
            // lose the chapter, and the plain block is still perfectly readable.
            warnings.add(
                Warning(
                    "Code block could not be highlighted (${e::class.simpleName}). " +
                        "It is rendered without highlighting."
                )
            )
            return escapeHtml(literal)
        }

        return spans.finish()
    }

    /**
     * Builds the escaped code, opening and closing `span`s as the color changes. Adjacent tokens
     * that resolve to the same color end up in one span.
     */
    private class SpanWriter {
        private val out = StringBuilder()
        private var open: String? = null

        fun append(color: String?, text: String) {
            if (text.isEmpty()) return
            if (color != open) {
                if (open != null) out.append("</span>")
                if (color != null) out.append("<span style=\"color:$color\">")
                open = color
            }
            out.append(escapeHtml(text))
        }

        /** Text that belongs to whichever span is currently open, and never opens or closes one. */
        fun appendInsideSpan(text: String) {
            out.append(text)
        }

        fun finish(): String {
            if (open != null) out.append("</span>")
            return out.toString()
        }
    }

    private companion object {

        /**
         * Token color by `TokenTypes` constant, on the `#f6f8fa` box. A type that is absent gets no
         * span at all
         */
        val COLORS: Map<Int, String> = mapOf(
            TokenTypes.COMMENT_EOL to "#6a737d",
            TokenTypes.COMMENT_MULTILINE to "#6a737d",
            TokenTypes.COMMENT_DOCUMENTATION to "#6a737d",
            TokenTypes.COMMENT_KEYWORD to "#6a737d",
            TokenTypes.COMMENT_MARKUP to "#6a737d",

            TokenTypes.RESERVED_WORD to "#d73a49",
            TokenTypes.RESERVED_WORD_2 to "#d73a49",
            TokenTypes.DATA_TYPE to "#d73a49",

            TokenTypes.FUNCTION to "#6f42c1",
            TokenTypes.ANNOTATION to "#6f42c1",
            TokenTypes.PREPROCESSOR to "#6f42c1",

            TokenTypes.LITERAL_BOOLEAN to "#005cc5",
            TokenTypes.LITERAL_NUMBER_DECIMAL_INT to "#005cc5",
            TokenTypes.LITERAL_NUMBER_FLOAT to "#005cc5",
            TokenTypes.LITERAL_NUMBER_HEXADECIMAL to "#005cc5",

            TokenTypes.LITERAL_STRING_DOUBLE_QUOTE to "#032f62",
            TokenTypes.LITERAL_CHAR to "#032f62",
            TokenTypes.LITERAL_BACKQUOTE to "#032f62",
            TokenTypes.REGEX to "#032f62",

            TokenTypes.VARIABLE to "#e36209",

            TokenTypes.MARKUP_TAG_DELIMITER to "#22863a",
            TokenTypes.MARKUP_TAG_NAME to "#22863a",
            TokenTypes.MARKUP_TAG_ATTRIBUTE to "#6f42c1",
            TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE to "#032f62",
            TokenTypes.MARKUP_COMMENT to "#6a737d",
            TokenTypes.MARKUP_DTD to "#6a737d",
            TokenTypes.MARKUP_PROCESSING_INSTRUCTION to "#6a737d",
            TokenTypes.MARKUP_CDATA_DELIMITER to "#6a737d",
            TokenTypes.MARKUP_ENTITY_REFERENCE to "#005cc5",

            TokenTypes.ERROR_IDENTIFIER to "#b31d28",
            TokenTypes.ERROR_NUMBER_FORMAT to "#b31d28",
            TokenTypes.ERROR_STRING_DOUBLE to "#b31d28",
            TokenTypes.ERROR_CHAR to "#b31d28"
        )

        /**
         * What an author writes on a fence, mapped onto RSyntaxTextArea's syntax styles. Anything not
         * in here is warned about once and rendered plain; `text`/`plain` map onto
         * [SyntaxConstants.SYNTAX_STYLE_NONE] on purpose, so "this is not code" says so without
         * producing a warning.
         */
        val SYNTAX_STYLES: Map<String, String> = mapOf(
            "text" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "plain" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "plaintext" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "txt" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "none" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "output" to SyntaxConstants.SYNTAX_STYLE_NONE,
            "actionscript" to SyntaxConstants.SYNTAX_STYLE_ACTIONSCRIPT,
            "asm" to SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86,
            "assembly" to SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86,
            "nasm" to SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86,
            "c" to SyntaxConstants.SYNTAX_STYLE_C,
            "h" to SyntaxConstants.SYNTAX_STYLE_C,
            "clj" to SyntaxConstants.SYNTAX_STYLE_CLOJURE,
            "clojure" to SyntaxConstants.SYNTAX_STYLE_CLOJURE,
            "c++" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            "cc" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            "cpp" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            "cxx" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            "hpp" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
            "c#" to SyntaxConstants.SYNTAX_STYLE_CSHARP,
            "cs" to SyntaxConstants.SYNTAX_STYLE_CSHARP,
            "csharp" to SyntaxConstants.SYNTAX_STYLE_CSHARP,
            "css" to SyntaxConstants.SYNTAX_STYLE_CSS,
            "csv" to SyntaxConstants.SYNTAX_STYLE_CSV,
            "d" to SyntaxConstants.SYNTAX_STYLE_D,
            "dart" to SyntaxConstants.SYNTAX_STYLE_DART,
            "delphi" to SyntaxConstants.SYNTAX_STYLE_DELPHI,
            "pascal" to SyntaxConstants.SYNTAX_STYLE_DELPHI,
            "docker" to SyntaxConstants.SYNTAX_STYLE_DOCKERFILE,
            "dockerfile" to SyntaxConstants.SYNTAX_STYLE_DOCKERFILE,
            "dtd" to SyntaxConstants.SYNTAX_STYLE_DTD,
            // RSyntaxTextArea 3.6.0 has no `.env` mode; a properties file is the same shape.
            "dotenv" to SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
            "env" to SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
            "f90" to SyntaxConstants.SYNTAX_STYLE_FORTRAN,
            "fortran" to SyntaxConstants.SYNTAX_STYLE_FORTRAN,
            "go" to SyntaxConstants.SYNTAX_STYLE_GO,
            "golang" to SyntaxConstants.SYNTAX_STYLE_GO,
            "gradle" to SyntaxConstants.SYNTAX_STYLE_GROOVY,
            "groovy" to SyntaxConstants.SYNTAX_STYLE_GROOVY,
            "handlebars" to SyntaxConstants.SYNTAX_STYLE_HANDLEBARS,
            "hbs" to SyntaxConstants.SYNTAX_STYLE_HANDLEBARS,
            "htaccess" to SyntaxConstants.SYNTAX_STYLE_HTACCESS,
            "htm" to SyntaxConstants.SYNTAX_STYLE_HTML,
            "html" to SyntaxConstants.SYNTAX_STYLE_HTML,
            "xhtml" to SyntaxConstants.SYNTAX_STYLE_HTML,
            "cfg" to SyntaxConstants.SYNTAX_STYLE_INI,
            "ini" to SyntaxConstants.SYNTAX_STYLE_INI,
            // Not the same language, but close enough to read: `# comment`, `[section]`, `key = value`.
            "toml" to SyntaxConstants.SYNTAX_STYLE_INI,
            "java" to SyntaxConstants.SYNTAX_STYLE_JAVA,
            "cjs" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
            "javascript" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
            "js" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
            "jsx" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
            "mjs" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
            "json" to SyntaxConstants.SYNTAX_STYLE_JSON,
            "json5" to SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS,
            "jsonc" to SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS,
            "jsp" to SyntaxConstants.SYNTAX_STYLE_JSP,
            "kotlin" to SyntaxConstants.SYNTAX_STYLE_KOTLIN,
            "kt" to SyntaxConstants.SYNTAX_STYLE_KOTLIN,
            "kts" to SyntaxConstants.SYNTAX_STYLE_KOTLIN,
            "latex" to SyntaxConstants.SYNTAX_STYLE_LATEX,
            "tex" to SyntaxConstants.SYNTAX_STYLE_LATEX,
            "less" to SyntaxConstants.SYNTAX_STYLE_LESS,
            "elisp" to SyntaxConstants.SYNTAX_STYLE_LISP,
            "lisp" to SyntaxConstants.SYNTAX_STYLE_LISP,
            "scheme" to SyntaxConstants.SYNTAX_STYLE_LISP,
            "lua" to SyntaxConstants.SYNTAX_STYLE_LUA,
            "make" to SyntaxConstants.SYNTAX_STYLE_MAKEFILE,
            "makefile" to SyntaxConstants.SYNTAX_STYLE_MAKEFILE,
            "mk" to SyntaxConstants.SYNTAX_STYLE_MAKEFILE,
            "markdown" to SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            "md" to SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            "mxml" to SyntaxConstants.SYNTAX_STYLE_MXML,
            "nsis" to SyntaxConstants.SYNTAX_STYLE_NSIS,
            "perl" to SyntaxConstants.SYNTAX_STYLE_PERL,
            "pl" to SyntaxConstants.SYNTAX_STYLE_PERL,
            "php" to SyntaxConstants.SYNTAX_STYLE_PHP,
            // No PowerShell mode in 3.6.0, and nothing close enough to fake it with: `powershell`
            "properties" to SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE,
            "proto" to SyntaxConstants.SYNTAX_STYLE_PROTO,
            "protobuf" to SyntaxConstants.SYNTAX_STYLE_PROTO,
            "py" to SyntaxConstants.SYNTAX_STYLE_PYTHON,
            "python" to SyntaxConstants.SYNTAX_STYLE_PYTHON,
            "rb" to SyntaxConstants.SYNTAX_STYLE_RUBY,
            "ruby" to SyntaxConstants.SYNTAX_STYLE_RUBY,
            "rs" to SyntaxConstants.SYNTAX_STYLE_RUST,
            "rust" to SyntaxConstants.SYNTAX_STYLE_RUST,
            "sas" to SyntaxConstants.SYNTAX_STYLE_SAS,
            "scala" to SyntaxConstants.SYNTAX_STYLE_SCALA,
            "sql" to SyntaxConstants.SYNTAX_STYLE_SQL,
            "tcl" to SyntaxConstants.SYNTAX_STYLE_TCL,
            "ts" to SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT,
            "tsx" to SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT,
            "typescript" to SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT,
            "bash" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
            "console" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
            "sh" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
            "shell" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
            "zsh" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
            "vb" to SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC,
            "vbnet" to SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC,
            "vhdl" to SyntaxConstants.SYNTAX_STYLE_VHDL,
            "bat" to SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH,
            "batch" to SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH,
            "cmd" to SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH,
            "plist" to SyntaxConstants.SYNTAX_STYLE_XML,
            "pom" to SyntaxConstants.SYNTAX_STYLE_XML,
            "svg" to SyntaxConstants.SYNTAX_STYLE_XML,
            "xml" to SyntaxConstants.SYNTAX_STYLE_XML,
            "xsl" to SyntaxConstants.SYNTAX_STYLE_XML,
            "yaml" to SyntaxConstants.SYNTAX_STYLE_YAML,
            "yml" to SyntaxConstants.SYNTAX_STYLE_YAML
        )
    }
}
