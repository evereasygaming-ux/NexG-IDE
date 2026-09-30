package com.nexg.ide.domain.uri

/**
 * SAF URI arithmetic in pure Kotlin, with no `android.net.Uri`.
 *
 * ### Why this exists
 *
 * The Phase 2 storage audit found that a created project's files were written
 * into the *parent* directory instead of the project directory, while the
 * Explorer happily listed directories and the unit tests stayed green. The cause
 * was a single wrong predicate in `SafFsAdapter`:
 *
 * ```kotlin
 * private fun documentIdOf(uri: Uri) =
 *     if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
 *     else DocumentsContract.getDocumentId(uri)
 * ```
 *
 * `DocumentsContract.isTreeUri` does not mean "this URI is a tree". It means
 * "this URI *contains* a tree segment". A tree-based **document** URI —
 * `content://auth/tree/<treeId>/document/<docId>`, which is the form every
 * `FileNode` in this app uses — contains `/tree/`, so the predicate returns true
 * and `getTreeDocumentId` returns `<treeId>`: the *parent's* id, silently
 * discarding `<docId>`. `treeOf` had the mirror-image bug, returning the whole
 * document URI as if it were a tree.
 *
 * The two mistakes cancelled out for `query`, so files were readable, and
 * `createFile`/`createDirectory` then targeted the parent document. The result
 * was a template written flat into the parent: a sibling `app/`, `src/`, `main/`,
 * `com/`, `nexg/`, `template/` and loose `settings.gradle.kts`,
 * `AndroidManifest.xml`, `MainActivity.kt` next to it, with the project folder
 * itself empty. Nothing threw, so no error was ever shown.
 *
 * The logic is pulled out here for two reasons. It is the part that was wrong,
 * so it is the part that must be pinned by tests; and it has to be reachable
 * from `ProjectManager`, which is pure Kotlin by design and cannot import
 * `android.net.Uri`.
 *
 * ### URI shapes
 *
 * | Shape                              | Example                                            |
 * |------------------------------------|----------------------------------------------------|
 * | tree                               | `content://auth/tree/primary%3ADocuments`          |
 * | tree-based document (what we hold) | `content://auth/tree/T/document/primary%3AMyApp%2Fapp` |
 *
 * A tree-based document URI carries both, and which one a call needs is the
 * whole question: listing a folder needs its *document* id inside its *tree*,
 * while asking for the tree itself means discarding the document part.
 */
object SafUri {

    /**
     * True when the URI carries a tree segment, whether or not it also carries
     * a document part.
     *
     * This is deliberately *not* the same question as "is this a tree URI" —
     * conflating the two is the bug this object exists to prevent.
     */
    fun hasTree(uri: String): Boolean = uri.contains(TREE_SEGMENT)

    /** True when the URI also names a specific document inside its tree. */
    fun isDocumentBased(uri: String): Boolean = uri.contains(DOCUMENT_SEGMENT)

    /**
     * The tree a document belongs to: the URI with its document part removed.
     *
     * Idempotent, and a bare tree URI is returned unchanged, so callers can
     * normalise unconditionally instead of branching.
     */
    fun treeUriOf(uri: String): String = uri.substringBefore(DOCUMENT_SEGMENT)

    /**
     * The id of the document the URI names, percent-decoded.
     *
     * For a tree-based document URI this is the *document* id, never the tree
     * id. For a bare tree URI there is no document part, so the tree's own id is
     * the document being addressed.
     */
    fun documentIdOf(uri: String): String {
        val raw = if (isDocumentBased(uri)) {
            uri.substringAfter(DOCUMENT_SEGMENT).substringBefore('?')
        } else {
            uri.substringAfter(TREE_SEGMENT, "").substringBefore('?')
        }
        return percentDecode(raw)
    }

    /** The tree's own id, percent-decoded. `null` when the URI has no tree. */
    fun treeDocumentIdOf(uri: String): String? {
        val tree = treeUriOf(uri)
        if (!hasTree(tree)) return null
        return percentDecode(tree.substringAfter(TREE_SEGMENT).substringBefore('?'))
    }

    /**
     * The document part of a tree-based URI, or `null` for a bare tree.
     *
     * Needed to rebuild `tree/document/doc` URIs without going through Android.
     */
    fun documentIdOrNull(uri: String): String? =
        if (isDocumentBased(uri)) documentIdOf(uri) else null

    // ------------------------------------------------------------------ platform

    /**
     * How *Android* splits a URI path, as `Uri.getPathSegments()` does: split
     * on `/` **first**, then percent-decode each segment, dropping empties.
     *
     * Modelled here in pure Kotlin because the order of those two operations is
     * the whole difference between a URI the provider understands and one it
     * silently misreads. Decoding first would turn an encoded `%2F` inside a
     * document id into a separator; the platform does the opposite, so a `%2F`
     * stays inside its segment.
     */
    fun platformPathSegments(uri: String): List<String> {
        val afterAuthority = uri.substringAfter("//", missingDelimiterValue = "")
        if (afterAuthority.isEmpty()) return emptyList()
        val path = afterAuthority.substringAfter('/', missingDelimiterValue = "")
        if (path.isEmpty()) return emptyList()
        return path.split('/').filter { it.isNotEmpty() }.map { percentDecode(it) }
    }

    /**
     * The tree id *Android* would read, per `DocumentsContract.getTreeDocumentId`.
     *
     * Which is to say: **the second path segment, and nothing else.** If a
     * document id's `%2F` has already been decoded, the tree id's real `/` has
     * become a separator, this returns the part before it, and the URI now names
     * a different tree than the one the grant was taken on.
     */
    fun platformTreeDocumentIdOf(uri: String): String? {
        val segments = platformPathSegments(uri)
        if (segments.size < 2 || segments[0] != TREE_SEGMENT_NAME) return null
        return segments[1]
    }

    /**
     * The document id *Android* would read, per `DocumentsContract.getDocumentId`:
     * everything after the first segment literally named `document`.
     *
     * Unlike the tree id, this one is robust to a decoded `/`, because the id is
     * rejoined from every remaining segment rather than read at a fixed index —
     * so a document id that loses its encoding still yields the right id. That
     * asymmetry is precisely what made the Phase 3 defect hard to see, and why
     * the tree id is the one worth checking. Modelled faithfully anyway: a
     * `document` *directory* inside the path is not a hazard here, because the
     * URI's own marker always precedes it.
     */
    fun platformDocumentIdOf(uri: String): String? {
        val segments = platformPathSegments(uri)
        val index = segments.indexOf(DOCUMENT_SEGMENT_NAME)
        if (index == -1 || index == segments.lastIndex) return null
        return percentDecode(segments.subList(index + 1, segments.size).joinToString("/"))
    }

    /**
     * Whether [uri] is a tree-based SAF document URI that Android will read back
     * as the ids it appears to name.
     *
     * The Phase 3 device run failed with "Permission or credential problem (at
     * reading a file)" on a file the Explorer had just listed successfully. The
     * URI had been percent-decoded one time too many on its way through the
     * navigation route, so its ids no longer survived their own encoding — and
     * the tolerant parsing above happily returned the *intended* ids for it,
     * which is exactly why the app forwarded a URI the provider was going to
     * reject instead of noticing the corruption itself.
     *
     * So the check is not "do the ids look plausible" but the sharper one:
     * **do Android's own parser and this one agree?** A URI where they disagree
     * is one this app would mis-read and the provider would refuse, and it must
     * be reported as malformed rather than as a permission problem the user
     * cannot act on.
     */
    fun isTreeBasedDocumentUri(uri: String): Boolean {
        if (!uri.startsWith(CONTENT_SCHEME)) return false
        if (!isDocumentBased(uri)) return false
        val intendedTree = treeDocumentIdOf(uri)
        val intendedDocument = documentIdOf(uri)
        if (intendedTree.isNullOrBlank() || intendedDocument.isBlank()) return false
        return platformTreeDocumentIdOf(uri) == intendedTree &&
            platformDocumentIdOf(uri) == intendedDocument
    }

    /**
     * The SAF document URI carried by a navigation route argument.
     *
     * Identity **on purpose**, and that is the entire fix for the Phase 3 device
     * bug. Navigation Compose percent-encodes a typed argument when it serialises
     * it and percent-decodes it when it matches the route, so a URI that
     * `NavRoute.Editor.path` encoded arrives here already decoded — exactly once.
     *
     * Decoding it a second time is what broke it. Encoding is not idempotent in
     * reverse: a second pass turns `primary%3ADocuments%2FMyApp` into
     * `primary:Documents/MyApp`, whose `/` the platform then reads as a path
     * separator, leaving the tree id as `primary:Documents` — a tree this app was
     * never granted. The file opened to "permission denied" while the very same
     * folder listed fine one screen earlier, because the Explorer rebuilds every
     * child URI from the provider's own document ids and never forwards the
     * string it was given.
     *
     * Kept as a named function, rather than just deleting the decode at the call
     * site, so the contract has an address, a name and a test — the mistake was
     * not noticing a decode, it was not having anywhere to record that one had
     * already happened.
     */
    fun fromRouteArgument(argument: String): String = argument

    private const val CONTENT_SCHEME = "content://"
    private const val DOCUMENT_SEGMENT = "/document/"
    private const val TREE_SEGMENT = "/tree/"
    private const val DOCUMENT_SEGMENT_NAME = "document"
    private const val TREE_SEGMENT_NAME = "tree"

    /**
     * Identity of the folder a URI points at, independent of which tree it was
     * reached through.
     *
     * A folder created inside a project and the same folder later re-picked by
     * the user are the same directory, but they arrive as different strings:
     * `…/tree/T/document/primary%3AMyApp` versus `…/tree/primary%3AMyApp`. The
     * project row is keyed on the full string, so one folder can become two
     * projects. Comparing the document id collapses them.
     *
     * Used only for *matching*; the stored `rootUri` stays a tree-based document
     * URI, because a re-rooted tree URI is not covered by the grant taken on the
     * original tree and would fail on a real provider.
     */
    fun folderIdentity(uri: String): String = documentIdOf(uri)

    /**
     * Minimal `%XX` decoder, matching `android.net.Uri.decode`.
     *
     * Hand-rolled rather than `java.net.URLDecoder` because that decodes `+` as a
     * space, which is wrong for a path segment, and it throws on a malformed
     * escape — a provider is free to expose a document id containing a literal
     * `%`. Malformed escapes are left verbatim: a URI that will not decode is a
     * naming problem, not a reason to fail an open.
     *
     * A run of consecutive escapes is decoded **as UTF-8**, not byte by byte,
     * because that is what the platform does and a document id carries file
     * names. Decoding byte by byte turns `caf%C3%A9.kt` into `cafÃ©.kt` — a
     * plausible-looking id that names a different document than the provider
     * does, so a create, rename or lookup under a non-ASCII folder would address
     * the wrong thing and say nothing about why. Falls back to the byte-wise
     * reading when a run is not valid UTF-8, leaving a mangled id visible rather
     * than substituting replacement characters.
     */
    fun percentDecode(raw: String): String {
        if ('%' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '%') {
                out.append(raw[i])
                i++
                continue
            }
            val run = decodeRun(raw, i)
            if (run == null) {
                out.append(raw[i])
                i++
            } else {
                out.append(run.text)
                i = run.next
            }
        }
        return out.toString()
    }

    private class DecodedRun(val text: String, val next: Int)

    private fun decodeRun(raw: String, start: Int): DecodedRun? {
        val bytes = ArrayList<Byte>(4)
        var j = start
        while (j + 2 < raw.length && raw[j] == '%') {
            val value = raw.substring(j + 1, j + 3).toIntOrNull(16) ?: break
            bytes.add(value.toByte())
            j += 3
        }
        if (bytes.isEmpty()) return null
        val array = ByteArray(bytes.size) { bytes[it] }
        val asLatin1 = String(CharArray(array.size) { (array[it].toInt() and 0xFF).toChar() })
        // Pure ASCII needs no charset, and anything not valid UTF-8 keeps the
        // byte-wise reading so a mangled id stays visible.
        val text = if (array.all { (it.toInt() and 0xFF) < 0x80 }) {
            asLatin1
        } else {
            strictUtf8OrNull(array) ?: asLatin1
        }
        return DecodedRun(text, j)
    }

    /** `null` when the bytes are not valid UTF-8. */
    private fun strictUtf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    /**
     * The exact inverse of [percentDecode] for the characters a SAF document id
     * contains, following `android.net.Uri.encode`: everything outside
     * `[A-Za-z0-9_-!.~'()*]` is percent-encoded, `%` included.
     *
     * Present so a canonical tree-based document URI can be *built* in pure
     * Kotlin rather than only parsed, and so the navigation round trip can be
     * encoded and decoded by the same code that is tested. Encoding `%` is the
     * part that is easy to get wrong and expensive to get wrong: an id encoded
     * twice reads as a literal `%3A` in the document name, and an id decoded
     * twice reads as a path separator. Both are silent.
     *
     * Multi-byte characters are encoded as their **UTF-8 bytes**, matching
     * `Uri.encode`. This is not academic: document ids carry file names, and
     * encoding a code point as one `%XX` would make `Uri.decode` — which
     * reassembles UTF-8 — produce a replacement character, turning a name like
     * `café.kt` into a file that does not exist.
     */
    fun percentEncode(raw: String): String {
        val out = StringBuilder(raw.length)
        for (byte in raw.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val keep = value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code ||
                value in '0'.code..'9'.code || value.toChar() in UNRESERVED_BYTES
            if (keep) {
                out.append(value.toChar())
            } else {
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    private const val UNRESERVED_BYTES = "_-!.~'()*"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * The readable folder name at the end of a document id, or `null`.
     *
     * A SAF document id is `<volume>:<path within the volume>`, so the last
     * meaningful segment after a `:` or `/` split is the display name. A
     * provider may expose an opaque id with no readable tail, in which case this
     * returns `null` instead of inventing a name.
     */
    fun displayNameOf(uri: String): String? {
        val id = documentIdOf(uri)
        val cleaned = id.substringAfterLast(':').substringAfterLast('/').trim()
        return cleaned.ifBlank { null }
    }
}
