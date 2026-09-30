package com.nexg.ide.ui.nav

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.domain.uri.SafUri
import org.junit.Test

/**
 * The Explorer → Editor URI handoff, pinned end to end.
 *
 * ## What broke on the device
 *
 * The Phase 3 device run produced a fully working Explorer and a file the
 * Explorer had just listed successfully:
 *
 * ```
 * MainActivity.kt exists at app/src/main/java/com/nexg/template/MainActivity.kt
 * tap it  ->  "No file is open"
 *          ->  "Permission or credential problem (at reading a file)"
 * ```
 *
 * The folder listed, and the same folder was written successfully during
 * project creation. So the grant was fine, the provider was fine and the file
 * was fine. The URI *string* was not.
 *
 * ## Why the Explorer survived it and the Editor did not
 *
 * The two screens use a `FileNode.uri` in opposite ways.
 *
 * `SafFsAdapter.listDirectory` never forwards the string it was given. It takes
 * the tree out of the caller's URI and rebuilds every child from the provider's
 * own `COLUMN_DOCUMENT_ID`, so however mangled an incoming URI is, the Explorer
 * regenerates correct ones on the way down. It also never opens a stream, so it
 * needs no permission of its own.
 *
 * `SafFsAdapter.readText` does the opposite: `openInputStream(Uri.parse(uri))`
 * on the string exactly as received. Whatever the Explorer tolerated, the
 * Editor hands to the provider verbatim.
 *
 * That is the whole shape of the bug — a URI that only fails when it is used
 * rather than merely carried is invisible to any test that lists a folder and
 * explains to nobody why opening one file fails.
 *
 * ## The mechanism
 *
 * `NavRoute.Editor.path` percent-encodes the URI (it must: a document id
 * contains `/` and `:`), Navigation Compose percent-decodes the argument when
 * it matches the route, and `editorArgsOf` decoded it a **third** time. Encoding
 * is not idempotent in reverse, so `primary%3ADocuments%2FMyApp` became
 * `primary:Documents/MyApp` — and the `/` is now a path separator.
 *
 * `DocumentsContract.getTreeDocumentId` returns the *second path segment, and
 * nothing else*. The URI now names the tree `primary:Documents` instead of
 * `primary:Documents/MyApp`, which is a different tree, so the persisted grant
 * no longer covers the URI and the provider answers `SecurityException` —
 * surfaced as `Kind.SECURITY` + step `"reading a file"`, i.e. exactly the two
 * strings the device showed.
 *
 * Note the trap this test also pins: the **document** id of the corrupted URI
 * still comes out correct, because `getDocumentId` rejoins everything after the
 * first `document` segment. So the URI is *self-inconsistent* — the id this app
 * reads, the tree Android reads, and the grant the app holds all disagree in a
 * way where the error names a permission rather than a bug.
 *
 * ## The models
 *
 * `android.net.Uri` and `android.os.Bundle` are not available to plain JVM unit
 * tests, and the project has no Robolectric, so `Uri.encode`/`Uri.decode` and
 * Navigation's decode are reproduced below in pure Kotlin. Each carries a
 * comment naming the exact platform behaviour it stands in for, and
 * [SafUri.platformPathSegments] / [SafUri.platformTreeDocumentIdOf] model the
 * platform's parser on the production side, so the two halves of this test are
 * an independent implementation of the same specification.
 */
class EditorUriHandoffTest {

    private val authority = "com.android.externalstorage.documents"
    private val treeId = "primary:Documents/MyApp"
    private val documentId = "primary:Documents/MyApp/app/src/main/java/com/nexg/template/MainActivity.kt"

    /** What `SafFsAdapter.toNode` builds: `buildDocumentUriUsingTree`. */
    private val explorerUri =
        "content://$authority/tree/${encode(treeId)}/document/${encode(documentId)}"

    // ------------------------------------------------------- platform URI models

    /**
     * `android.net.Uri.encode`: percent-encode everything outside
     * `[A-Za-z0-9_-!.~'()*]`, including `%` itself. ASCII is enough for a SAF
     * document id, so bytes are encoded one at a time.
     */
    private fun encode(raw: String): String = SafUri.percentEncode(raw)

    /** `android.net.Uri.decode`: a single `%XX` pass, `+` left alone. */
    private fun decode(raw: String): String = SafUri.percentDecode(raw)

    /**
     * The route argument as Navigation hands it to the app: `Uri.encode` in
     * `NavRoute.Editor.path`, then exactly one `Uri.decode` when the route is
     * matched. Verified against navigation-common 2.8.5 —
     * `NavType$StringType.serializeAsValue` calls `Uri.encode`,
     * `NavType$StringType.parseValue` is the identity, and the decode lives in
     * `NavDeepLink`'s argument extraction.
     */
    private fun navigationArgument(uri: String): String = decode(encode(uri))

    // ------------------------------------------------- the Explorer → Editor trip

    @Test
    fun `the uri the explorer lists is one android reads back correctly`() {
        // The premise. If this fails, the Explorer and the provider already
        // disagreed and the "working Explorer" reading of the device bug is wrong.
        assertThat(SafUri.isTreeBasedDocumentUri(explorerUri)).isTrue()
        assertThat(SafUri.platformTreeDocumentIdOf(explorerUri)).isEqualTo(treeId)
        assertThat(SafUri.platformDocumentIdOf(explorerUri)).isEqualTo(documentId)
    }

    @Test
    fun `the editor opens byte for byte the string the explorer listed`() {
        // The property the bug was about, exercised through the *shipped* route
        // code rather than a model of it: build the route the way the Explorer
        // does, take the argument the way the framework does, and read it the
        // way `editorArgsOf` does.
        val route = NavRoute.Editor.path(explorerUri, "MainActivity.kt")
        val argument = route.substringAfter("uri=").substringBefore("&")
        val fromFramework = decode(argument)
        val (openedUri, openedName) =
            NavRoute.editorFileFromArguments(fromFramework, "MainActivity.kt")!!

        assertThat(openedUri).isEqualTo(explorerUri)
        assertThat(openedName).isEqualTo("MainActivity.kt")
        assertThat(SafUri.isTreeBasedDocumentUri(openedUri)).isTrue()
    }

    @Test
    fun `a non ascii file name survives the round trip`() {
        // A document id carries a file name, and a name is not ASCII in a real
        // device. `Uri.decode` reassembles UTF-8, so encoding a code point as one
        // `%XX` would round-trip to a replacement character and the editor would
        // open a file that does not exist — the same class of failure as the one
        // being fixed, one character class over.
        val name = "café.kt"
        val unicodeUri = "content://$authority/tree/${encode(treeId)}/" +
            "document/${encode("primary:Documents/MyApp/$name")}"
        val route = NavRoute.Editor.path(unicodeUri, name)
        val (openedUri, openedName) = NavRoute.editorFileFromArguments(
            decode(route.substringAfter("uri=").substringBefore("&")),
            decode(route.substringAfter("name=")),
        )!!

        assertThat(openedName).isEqualTo(name)
        assertThat(openedUri).isEqualTo(unicodeUri)
        assertThat(SafUri.platformDocumentIdOf(openedUri)).isEqualTo("primary:Documents/MyApp/$name")
    }

    @Test
    fun `a missing argument yields no file rather than a broken one`() {
        assertThat(NavRoute.editorFileFromArguments(null, "Main.kt")).isNull()
        assertThat(NavRoute.editorFileFromArguments(explorerUri, null)).isNull()
    }

    @Test
    fun `a second decode is what breaks the tree the grant was taken on`() {
        // The bug, reproduced exactly as the device hit it.
        val doubleDecoded = decode(navigationArgument(explorerUri))
        assertThat(doubleDecoded).isNotEqualTo(explorerUri)

        // The tree id this app still believes it is addressing…
        assertThat(SafUri.treeDocumentIdOf(doubleDecoded)).isEqualTo(treeId)
        // …is not the tree id the platform will read, which is the whole point.
        assertThat(SafUri.platformTreeDocumentIdOf(doubleDecoded))
            .isNotEqualTo(SafUri.platformTreeDocumentIdOf(explorerUri))
        // It is truncated at the decoded `/`, i.e. the volume root.
        assertThat(SafUri.platformTreeDocumentIdOf(doubleDecoded))
            .isEqualTo("primary:Documents")

        // And so the provider refuses it: a URI outside the granted tree.
        assertThat(SafUri.isTreeBasedDocumentUri(doubleDecoded)).isFalse()
    }

    @Test
    fun `the corrupted uri still reports a correct document id, which is why it was invisible`() {
        // Why the failure was so confusing: half of the corrupted URI is fine.
        // A "did the id look right?" check passes, the folder lists, and only the
        // read fails — so nothing short of comparing against the *platform's*
        // parse catches it.
        val doubleDecoded = decode(navigationArgument(explorerUri))
        assertThat(SafUri.platformDocumentIdOf(doubleDecoded)).isEqualTo(documentId)
        assertThat(SafUri.isTreeBasedDocumentUri(doubleDecoded)).isFalse()
    }

    @Test
    fun `safuri is more tolerant than android, which is what hid the defect`() {
        // The reason the app forwarded a URI the provider would reject: the
        // tolerant parser returned the intended ids for a URI that no longer
        // encoded them, so nothing upstream could tell it was broken.
        val doubleDecoded = decode(navigationArgument(explorerUri))
        assertThat(SafUri.treeDocumentIdOf(doubleDecoded)).isEqualTo(treeId)
        assertThat(SafUri.platformTreeDocumentIdOf(doubleDecoded))
            .isNotEqualTo(SafUri.treeDocumentIdOf(doubleDecoded))
    }

    // ----------------------------------------------------- platform corner cases

    @Test
    fun `a bare tree uri is not a document uri`() {
        val bare = "content://$authority/tree/${encode(treeId)}"
        assertThat(SafUri.isTreeBasedDocumentUri(bare)).isFalse()
    }

    @Test
    fun `a document based uri outside any tree is not a document uri`() {
        val bare = "content://$authority/document/${encode(documentId)}"
        assertThat(SafUri.isTreeBasedDocumentUri(bare)).isFalse()
    }

    @Test
    fun `a non content scheme is rejected`() {
        assertThat(SafUri.isTreeBasedDocumentUri(explorerUri.replace("content", "file")))
            .isFalse()
    }

    @Test
    fun `an encoded separator inside a document id never becomes a path separator`() {
        // The property the whole scheme rests on, and the reason the tree id is
        // the one worth checking: `getDocumentId` rejoins from the marker, so it
        // survives a lost encoding, whereas `getTreeDocumentId` reads a fixed
        // index and does not. A `document` *directory* in the path is harmless,
        // because the URI's own marker always comes first.
        val idWithDocumentFolder = "primary:MyApp/document/nested"
        val uri = "content://$authority/tree/${encode(treeId)}/" +
            "document/${encode(idWithDocumentFolder)}"
        assertThat(SafUri.platformDocumentIdOf(uri)).isEqualTo(idWithDocumentFolder)
        assertThat(SafUri.isTreeBasedDocumentUri(uri)).isTrue()
    }
}
