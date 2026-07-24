package cz.preclikos.tvhstream.core

/**
 * Resolves the Coil model used to load a channel icon.
 *
 * TVHeadend may report a channel icon either as a server-relative path
 * (e.g. `imagecache/123`, `picon/...`) which we open through HTSP, or as an
 * `http(s)://` URL from the channel's "User icon" field which Coil loads directly.
 */
fun resolvePiconModel(serverTag: String, piconPath: String?): String? {
    if (serverTag.isBlank() || piconPath.isNullOrBlank()) return null

    val trimmed = piconPath.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }

    val p = trimmed.trimStart('/')
    return "htsp-picon://$serverTag/$p"
}
