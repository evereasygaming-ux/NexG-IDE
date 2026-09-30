package com.nexg.ide.domain.uri

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SafUri] rules, pinned by tests because the bug they prevent was silent.
 *
 * The Phase 2 device report was "the project was created but the Android files
 * were not there". The cause was that a tree-based *document* URI — the shape
 * every `FileNode` uses — was read as if it were a bare tree, so its document id
 * was replaced by its parent's tree id. Every create and every listing then
 * addressed the parent folder: a correct-looking tree, one level too high, and no
 * exception anywhere. A test that only checked "createProject returns Success"
 * passed straight through it, which is why the fake-filesystem tests had to be
 * joined by these string-level tests.
 */
class SafUriTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    private val document = "$tree/document/primary%3AMyApp"
    private val nested = "$tree/document/primary%3AMyApp%2Fapp%2Fsrc%2Fmain"

    // ------------------------------------------------------------ classification

    @Test
    fun `a document based uri is recognised as having a tree`() {
        // The premise of the whole bug: this is what `isTreeUri` reports too,
        // which is why it must never be used to mean "is a tree".
        assertThat(SafUri.hasTree(document)).isTrue()
        assertThat(SafUri.isDocumentBased(document)).isTrue()
        assertThat(SafUri.isDocumentBased(tree)).isFalse()
    }

    @Test
    fun `a bare tree uri is recognised as a tree and not as a document`() {
        assertThat(SafUri.hasTree(tree)).isTrue()
        assertThat(SafUri.isDocumentBased(tree)).isFalse()
    }

    // -------------------------------------------------------------- tree / doc id

    @Test
    fun `tree of a document based uri drops the document part`() {
        assertThat(SafUri.treeUriOf(document)).isEqualTo(tree)
    }

    @Test
    fun `tree of a bare tree uri is itself`() {
        assertThat(SafUri.treeUriOf(tree)).isEqualTo(tree)
    }

    @Test
    fun `tree extraction is idempotent`() {
        // Callers normalise unconditionally, so a second pass must not change
        // the URI again.
        val once = SafUri.treeUriOf(document)
        assertThat(SafUri.treeUriOf(once)).isEqualTo(once)
    }

    @Test
    fun `document id of a document based uri is the document, not the tree`() {
        // THE regression. Reading this as the tree id yields "primary:Documents"
        // and silently writes the project one folder too high.
        assertThat(SafUri.documentIdOf(document)).isEqualTo("primary:MyApp")
    }

    @Test
    fun `document id of a nested document uri keeps the full path`() {
        assertThat(SafUri.documentIdOf(nested)).isEqualTo("primary:MyApp/app/src/main")
    }

    @Test
    fun `document id of a bare tree uri is the tree's own id`() {
        assertThat(SafUri.documentIdOf(tree)).isEqualTo("primary:Documents")
    }

    @Test
    fun `tree document id ignores a document part`() {
        assertThat(SafUri.treeDocumentIdOf(document)).isEqualTo("primary:Documents")
        assertThat(SafUri.treeDocumentIdOf(tree)).isEqualTo("primary:Documents")
    }

    @Test
    fun `tree document id is null when there is no tree`() {
        assertThat(SafUri.treeDocumentIdOf("content://authority/document/primary%3AX")).isNull()
    }

    @Test
    fun `document id or null distinguishes the two shapes`() {
        assertThat(SafUri.documentIdOrNull(document)).isEqualTo("primary:MyApp")
        assertThat(SafUri.documentIdOrNull(tree)).isNull()
    }

    // ------------------------------------------------------------ folder identity

    @Test
    fun `a created folder and the same folder re-picked share an identity`() {
        // Created:     …/tree/primary%3ADocuments/document/primary%3AMyApp
        // Re-picked:   …/tree/primary%3AMyApp
        // Different strings, one directory. This is what stops one folder from
        // becoming two project rows.
        val rePicked = "content://com.android.externalstorage.documents/tree/primary%3AMyApp"
        assertThat(rePicked).isNotEqualTo(document)
        assertThat(SafUri.folderIdentity(rePicked)).isEqualTo(SafUri.folderIdentity(document))
    }

    @Test
    fun `different folders have different identities`() {
        val other = "content://com.android.externalstorage.documents/tree/primary%3AOther"
        assertThat(SafUri.folderIdentity(other)).isNotEqualTo(SafUri.folderIdentity(document))
    }

    @Test
    fun `a nested folder is not confused with its parent`() {
        // The parent id is a prefix of the child id, so a prefix comparison
        // would merge every ancestor into one project.
        assertThat(SafUri.folderIdentity(document)).isNotEqualTo(SafUri.folderIdentity(nested))
    }

    // ------------------------------------------------------------------ decoding

    @Test
    fun `percent escapes are decoded`() {
        assertThat(SafUri.percentDecode("primary%3AMy%20App")).isEqualTo("primary:My App")
    }

    @Test
    fun `a plus sign is not treated as a space`() {
        // A path segment keeps its literal '+'; java.net.URLDecoder would
        // corrupt any folder named with one.
        assertThat(SafUri.percentDecode("primary%3Aa+b")).isEqualTo("primary:a+b")
    }

    @Test
    fun `a malformed escape is left verbatim rather than throwing`() {
        assertThat(SafUri.percentDecode("primary%3A100%zz")).isEqualTo("primary:100%zz")
    }

    @Test
    fun `a string without escapes is returned unchanged`() {
        assertThat(SafUri.percentDecode("primary:MyApp")).isEqualTo("primary:MyApp")
    }

    // -------------------------------------------------------------- display name

    @Test
    fun `display name is the tail after the volume`() {
        assertThat(SafUri.displayNameOf(document)).isEqualTo("MyApp")
    }

    @Test
    fun `display name works for a bare tree uri`() {
        assertThat(SafUri.displayNameOf(tree)).isEqualTo("Documents")
    }

    @Test
    fun `display name of a nested folder is the folder, not the root`() {
        assertThat(SafUri.displayNameOf(nested)).isEqualTo("main")
    }

    @Test
    fun `display name of an opaque id is null rather than invented`() {
        // A provider may expose an id with no readable tail. Inventing a name
        // would put something meaningless in the project list.
        assertThat(SafUri.displayNameOf("content://authority/tree/%2F")).isNull()
    }

    @Test
    fun `a query string is not part of the document id`() {
        assertThat(SafUri.documentIdOf("$document?rev=3")).isEqualTo("primary:MyApp")
    }
}
