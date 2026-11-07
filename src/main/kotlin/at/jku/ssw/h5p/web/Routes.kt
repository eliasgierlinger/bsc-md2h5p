package at.jku.ssw.h5p.web

import at.jku.ssw.h5p.Warning
import at.jku.ssw.h5p.WorkDirs
import at.jku.ssw.h5p.converter.ConversionResult
import at.jku.ssw.h5p.converter.InputSource
import at.jku.ssw.h5p.converter.convert
import at.jku.ssw.h5p.input.GitRepoSource
import at.jku.ssw.h5p.input.UploadedFilesSource
import at.jku.ssw.h5p.merge.mergePackages
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a conversion answers with. The file itself is *not* the response body any more: the warnings
 * are as much a result of the conversion as the package is, and a file download cannot carry them.
 */
@Serializable
private data class ConversionResponse(
    val title: String,
    val fileName: String,
    val downloadUrl: String,
    val warnings: List<String>
)

@Serializable
private data class ErrorResponse(val error: String)

/** A finished conversion together with the title it ran under - which the upload only knows afterwards. */
private data class Conversion(val title: String, val result: ConversionResult)

/**
 * The scratch directory one request staged its input in - a clone or an upload.
 *
 * [converting] deletes it once the request is done with it, and the block registers it the moment it
 * exists rather than returning it, so a conversion that aborts halfway leaves nothing behind either.
 * Everything the book needs out of that directory was copied into the package while it was built,
 * and nothing downstream - the download route least of all - reads it again.
 */
private class Staging {
    private var dir: File? = null

    fun stage(dir: File): File = dir.also { this.dir = it }

    fun discard() {
        dir?.let(WorkDirs::discard)
    }
}

private val json = Json

fun Application.configureRouting() {
    routing {
        staticResources("/static", "web")

        get("/") {
            call.respondHtml { convertPage() }
        }

        post("/convert/repo") {
            val params = call.receiveParameters()
            val repoUrl = params["repoUrl"]?.takeIf { it.isNotBlank() }
            if (repoUrl == null) {
                call.respondError(HttpStatusCode.BadRequest, "Enter the URL of a public Git repository.")
                return@post
            }

            val subfolder = params["subfolder"]?.takeIf { it.isNotBlank() }
            val title = params["title"]?.trim()?.ifBlank { null } ?: GitRepoSource.repoNameFromUrl(repoUrl)

            call.converting { staging ->
                val sourceDir = staging.stage(GitRepoSource.clone(repoUrl))
                Conversion(title, convert(sourceDir, InputSource.GIT_REPO, title, subfolder = subfolder))
            }
        }

        post("/convert/upload") {
            call.converting { staging ->
                val upload = UploadedFilesSource.receive(call.receiveMultipart())
                staging.stage(upload.sourceDir)
                val title = upload.title ?: DEFAULT_UPLOAD_TITLE
                val converted = convert(upload.sourceDir, InputSource.UPLOADED_FILES, title)

                // Merging is post-processing on two finished packages, so the conversion above ran
                // exactly as it would have on its own (D26).
                val result = upload.existingPackage?.let { existing ->
                    val merged = mergePackages(converted.file, existing)
                    merged.copy(warnings = converted.warnings + merged.warnings)
                } ?: converted

                Conversion(title, result.copy(warnings = upload.warnings + result.warnings))
            }
        }

        get("/download/{token}") {
            val file = Downloads.find(call.parameters["token"])?.takeIf { it.isFile }
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, "That download has expired. Convert again.")
                return@get
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, file.name)
                    .toString()
            )
            call.respondFile(file)
        }
    }
}

/**
 * Runs one conversion and answers with its result, or with the reason it did not finish.
 *
 * Everything the pipeline itself objects to is already a [Warning] and travels in the response -
 * this only has to catch what aborts, which is a failed clone, an unreadable upload or a bug.
 */
private suspend fun ApplicationCall.converting(block: suspend (Staging) -> Conversion) {
    val staging = Staging()
    try {
        val conversion = block(staging)
        respondJson(
            HttpStatusCode.OK,
            ConversionResponse(
                title = conversion.title,
                fileName = conversion.result.file.name,
                downloadUrl = "/download/${Downloads.register(conversion.result.file)}",
                warnings = conversion.result.warnings.map(Warning::message)
            )
        )
    } catch (e: Exception) {
        // The browser gets the reason; the stack trace stays on the server, where it is of use.
        e.printStackTrace()
        respondError(HttpStatusCode.InternalServerError, "Conversion failed: ${e.message ?: e::class.simpleName}")
    } finally {
        staging.discard()
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondJson(status, ErrorResponse(message))

private suspend inline fun <reified T> ApplicationCall.respondJson(status: HttpStatusCode, body: T) =
    respondText(json.encodeToString(body), ContentType.Application.Json, status)

private fun HTML.convertPage() {
    attributes["lang"] = "en"

    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +"Markdown to H5P Converter" }
        link(rel = "icon", href = BOOK_FAVICON)
        link(rel = "stylesheet", href = "/static/style.css")
    }

    body {
        div("page") {
            header("masthead") {
                h1 {
                    +"Markdown "
                    span("arrow") { +"→" }
                    +" H5P"
                }
                p { +"Turns a folder of Markdown chapters into an H5P Interactive Book for Moodle." }
            }

            div("tabs") {
                attributes["role"] = "tablist"
                tab("tab-repo", "panel-repo", "Git repository", selected = true)
                tab("tab-upload", "panel-upload", "Files & folders", selected = false)
            }

            div("card card--tabbed") {
                repoPanel()
                uploadPanel()
            }

            p("notice notice--error") {
                id = "errorBox"
                attributes["hidden"] = "hidden"
            }

            section("card result") {
                id = "result"
                attributes["hidden"] = "hidden"

                h2 { id = "resultTitle" }
                div("filename") { id = "resultFileName" }
                a(href = "#", classes = "download") {
                    id = "downloadLink"
                    +"Download the .h5p"
                }
                div { id = "warnings" }
            }

            footer("colophon") {
                +"Upload the package in Moodle under "
                strong { +"Content bank → Upload" }
                +". Chapter order comes from a leading number in the file name ("
                code { +"1_intro.md" }
                +"); the title comes from the first heading."
            }
        }

        script(src = "/static/app.js") {}
    }
}

private fun kotlinx.html.DIV.tab(tabId: String, panelId: String, label: String, selected: Boolean) {
    button(type = ButtonType.button, classes = "tab") {
        id = tabId
        attributes["role"] = "tab"
        attributes["aria-selected"] = selected.toString()
        attributes["aria-controls"] = panelId
        +label
    }
}

/**
 * The book title, which is also what the downloaded file is named after. Optional in both panels,
 * with [fallback] naming what is used when it stays empty.
 */
private fun FlowContent.titleField(fieldId: String, fallback: String) {
    label("field") {
        span {
            +"Book title "
            span("optional") { +" optional, $fallback otherwise" }
        }
        input(type = InputType.text, name = "title") {
            id = fieldId
            placeholder = "Introduction to Compilers"
        }
    }
}

private fun kotlinx.html.DIV.repoPanel() {
    section("panel") {
        id = "panel-repo"
        attributes["role"] = "tabpanel"
        attributes["aria-labelledby"] = "tab-repo"

        p("hint") {
            +"The repository is cloned shallowly, so it has to be public. Every "
            code { +".md" }
            +" file in the chapter folder becomes one chapter."
        }

        form(method = FormMethod.post) {
            id = "repoForm"

            label("field") {
                span { +"Repository URL" }
                input(type = InputType.text, name = "repoUrl") {
                    id = "repoUrl"
                    placeholder = "https://github.com/user/repo"
                }
            }

            label("field") {
                span {
                    +"Subfolder "
                    span("optional") { +" optional, detected automatically when left empty" }
                }
                input(type = InputType.text, name = "subfolder") {
                    id = "subfolder"
                    placeholder = "docs/book"
                }
            }

            titleField("repoTitle", fallback = "the repository name")

            div("actions") {
                button(type = ButtonType.submit, classes = "button") {
                    id = "repoSubmit"
                    +"Convert"
                }
                span("status") { id = "repoStatus" }
            }
        }
    }
}

private fun kotlinx.html.DIV.uploadPanel() {
    section("panel") {
        id = "panel-upload"
        attributes["role"] = "tabpanel"
        attributes["aria-labelledby"] = "tab-upload"
        attributes["hidden"] = "hidden"

        p("hint") { +"Add files one by one, or select or drop a whole folder, images included." }

        div("dropzone") {
            id = "dropZone"
            strong { +"Drop files or a folder here" }
            small { +"nothing leaves your machine until you press Convert" }
        }

        div("actions") {
            button(type = ButtonType.button, classes = "button button--secondary") {
                id = "addFilesButton"
                +"Add file(s)"
            }
            button(type = ButtonType.button, classes = "button button--secondary") {
                id = "addFolderButton"
                +"Add folder"
            }
            span("filecount") { id = "fileCount" }
        }

        input(type = InputType.file, name = "files") {
            id = "fileInput"
            multiple = true
            attributes["hidden"] = "hidden"
        }
        input(type = InputType.file, name = "folderFiles") {
            id = "folderInput"
            multiple = true
            attributes["hidden"] = "hidden"
            attributes["webkitdirectory"] = "true"
        }

        ul("filelist") { id = "fileList" }

        titleField("uploadTitle", fallback = "“$DEFAULT_UPLOAD_TITLE”")

        div("field") {
            span {
                +"Existing package to update "
                span("optional") { +" optional, only for a book edited in H5P" }
            }
            div("actions packagepicker") {
                id = "packageZone"
                button(type = ButtonType.button, classes = "button button--secondary") {
                    id = "choosePackageButton"
                    +"Choose package"
                }
                span("filecount") { id = "packageName" }
                button(type = ButtonType.button, classes = "button--link") {
                    id = "removePackageButton"
                    attributes["hidden"] = "hidden"
                    +"remove"
                }
            }
            input(type = InputType.file, name = UploadedFilesSource.EXISTING_PACKAGE_FIELD) {
                id = "existingPackageInput"
                accept = ".h5p"
                attributes["hidden"] = "hidden"
            }
            small("hint") {
                +"Its quizzes, videos and anything else added in H5P are kept; the text is rebuilt "
                +"from the Markdown above."
            }
        }

        div("actions") {
            button(type = ButtonType.button, classes = "button") {
                id = "uploadSubmitButton"
                +"Convert"
            }
            span("status") { id = "uploadStatus" }
        }
    }
}

/** What an upload is called when the user does not name it - an upload has no folder name to borrow. */
private const val DEFAULT_UPLOAD_TITLE = "Uploaded Book"

/** An inline book glyph, so the tab has an icon without the page asking the network for one. */
private const val BOOK_FAVICON =
    "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
        "%3Ctext y='.9em' font-size='90'%3E📘%3C/text%3E%3C/svg%3E"
