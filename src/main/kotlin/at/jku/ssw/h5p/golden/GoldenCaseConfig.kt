package at.jku.ssw.h5p.golden

import kotlinx.serialization.Serializable

/**
 * Corresponds to case.json inside a golden-tests/cases/<name>/ folder.
 */
@Serializable
data class GoldenCaseConfig(
    val mode: String,
    val title: String,
    val subfolder: String? = null,
    /**
     * A folder inside the case holding an already-edited package, stored unpacked so it diffs like
     * the H5P template does. A committed package is impossible anyway - `.gitignore` excludes every
     * `.h5p` under `golden-tests`, and every `.zip`. The runner zips it and merges into it.
     */
    val mergeWith: String? = null,
    /** What this case is for. Documentation only - the runner never looks at it. */
    val note: String? = null
)
