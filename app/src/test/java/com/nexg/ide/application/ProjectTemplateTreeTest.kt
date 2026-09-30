package com.nexg.ide.application

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import com.nexg.ide.domain.uri.SafUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Proves the exact tree a new project produces, as the device will see it.
 *
 * The reason this file exists: the previous template writer had a URI bug that
 * wrote every file into the *parent* directory while `createProject` still
 * returned `Success`. "The template was written" was therefore not a fact anyone
 * could assert — the result object only said no error occurred. These tests
 * assert on the resulting *tree*, walking it the way the Explorer will, so a
 * misplaced directory or file fails here rather than on a phone.
 *
 * The project root is always taken from the returned [Project] rather than
 * written out by hand: the fake provider names a child by its display name, and
 * hardcoding a URI here would have tested the fake's naming instead of the
 * template.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTemplateTreeTest {

    private val fileSystem = FakeFileSystem()
    private val repository = FakeProjectRepository()
    private val sink = InMemoryLogSink()
    private val logger = AppLogger(sink = sink, minLevel = LogLevel.DEBUG)
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun manager() = object : ProjectManager(
        fileSystem = fileSystem,
        projects = repository,
        dispatchers = dispatchers,
        logger = logger,
    ) {
        override fun now(): Long = 1_000L
    }

    /** The folder the user picked in the SAF picker. */
    private val pickedParent = "content://auth/tree/primary%3ADocuments"

    private suspend fun create(name: String = "MyApp"): Project {
        fileSystem.addNode(uri = pickedParent, name = "Documents", isDirectory = true)
        val project = manager().createProject(pickedParent, name).getOrNull()
        return requireNotNull(project) { "createProject must succeed for this test" }
    }

    /**
     * Walks the generated tree from the project root, exactly as the Explorer
     * does, and returns every path relative to the project folder.
     */
    private suspend fun walkTree(rootUri: String): Set<String> {
        val out = mutableSetOf<String>()
        val queue = ArrayDeque(listOf("" to rootUri))
        while (queue.isNotEmpty()) {
            val (prefix, uri) = queue.removeFirst()
            val children = fileSystem.listDirectory(uri).getOrNull().orEmpty()
            for (child in children) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                out += path
                if (child.isDirectory) queue += path to child.uri
            }
        }
        return out
    }

    private fun textAt(rootUri: String, path: String) =
        fileSystem.textUnder(fileSystem.resolvePath(rootUri, path))

    // ------------------------------------------------------------ required files

    @Test
    fun `every required template file is present in the generated tree`() = runTest {
        val project = create()

        val tree = walkTree(project.rootUri)

        assertThat(tree).containsAtLeastElementsIn(ProjectTemplate.requiredFilePaths)
    }

    @Test
    fun `MainActivity is generated with real Kotlin content`() = runTest {
        val project = create()

        val text = textAt(project.rootUri, "app/src/main/java/com/nexg/template/MainActivity.kt")

        assertThat(text).contains("package com.nexg.template")
        assertThat(text).contains("class MainActivity")
        assertThat(text).contains("override fun onCreate")
    }

    @Test
    fun `AndroidManifest is generated and declares the launcher activity`() = runTest {
        val project = create()

        val text = textAt(project.rootUri, "app/src/main/AndroidManifest.xml")

        assertThat(text).contains("<manifest")
        assertThat(text).contains("android.intent.category.LAUNCHER")
        assertThat(text).contains(".MainActivity")
    }

    @Test
    fun `settings gradle is generated at the project root`() = runTest {
        val project = create()

        val text = textAt(project.rootUri, "settings.gradle.kts")

        assertThat(text).contains("include(\":app\")")
        assertThat(text).contains(ProjectTemplate.AGP_VERSION)
        assertThat(text).contains(ProjectTemplate.KOTLIN_VERSION)
    }

    @Test
    fun `root build gradle is generated`() = runTest {
        val project = create()

        val text = textAt(project.rootUri, "build.gradle.kts")

        assertThat(text).contains("com.android.application")
        assertThat(text).contains("apply false")
    }

    @Test
    fun `app build gradle is generated with the pinned matrix`() = runTest {
        val project = create()

        val text = textAt(project.rootUri, "app/build.gradle.kts")

        assertThat(text).contains("compileSdk = ${ProjectTemplate.COMPILE_SDK}")
        assertThat(text).contains("minSdk = ${ProjectTemplate.MIN_SDK}")
        assertThat(text).contains("targetSdk = ${ProjectTemplate.TARGET_SDK}")
        assertThat(text).contains("namespace = \"${ProjectTemplate.PACKAGE}\"")
    }

    @Test
    fun `gradle properties and README are generated`() = runTest {
        val project = create()

        assertThat(textAt(project.rootUri, "gradle.properties")).contains("android.useAndroidX=true")
        assertThat(textAt(project.rootUri, "README.md")).contains("Created by NexG IDE")
    }

    @Test
    fun `every template file has non-empty content`() = runTest {
        val project = create()

        for (file in ProjectTemplate.files) {
            assertThat(textAt(project.rootUri, file.relativePath)).isNotEmpty()
        }
    }

    // -------------------------------------------------------- required directories

    @Test
    fun `every required directory is present in the generated tree`() = runTest {
        val project = create()

        val tree = walkTree(project.rootUri)

        assertThat(tree).containsAtLeastElementsIn(ProjectTemplate.requiredDirectories)
    }

    @Test
    fun `res exists even though no file lives in it`() = runTest {
        val project = create()

        // The resource directory is part of the required skeleton but holds no
        // generated file, so deriving directories from files alone dropped it.
        val tree = walkTree(project.rootUri)

        assertThat(tree).contains("app/src/main/res")
        assertThat(tree).contains("app/src/main/res/values")
    }

    @Test
    fun `the java package path is fully created`() = runTest {
        val project = create()

        val tree = walkTree(project.rootUri)

        // Each level has to exist as its own directory; a missing `nexg` or
        // `template` step would put MainActivity somewhere unopenable.
        val segments = listOf("com", "nexg", "template")
        for (i in 1..segments.size) {
            val prefix = "app/src/main/java/" + segments.take(i).joinToString("/")
            assertThat(tree).contains(prefix)
        }
    }

    // ------------------------------------------------------- nothing misplaced

    @Test
    fun `nothing is written into the parent folder`() = runTest {
        // The actual defect: with the tree/document mix-up every segment was
        // created one level too high, so the parent held app/, src/, main/ and
        // loose Gradle files while the project folder stayed empty.
        create()

        val siblings = fileSystem.listDirectory(pickedParent).getOrNull().orEmpty()
            .map { it.name }
            .filter { it != "MyApp" }

        assertThat(siblings).isEmpty()
    }

    @Test
    fun `the project folder is not empty`() = runTest {
        val project = create()

        assertThat(fileSystem.listDirectory(project.rootUri).getOrNull()).isNotEmpty()
    }

    @Test
    fun `the generated tree is recognised as a complete project`() = runTest {
        val project = create()

        assertThat(ProjectTemplate.isCompleteProject(walkTree(project.rootUri))).isTrue()
    }

    @Test
    fun `the generated project is detected as an android gradle layout`() = runTest {
        val project = create()

        val reopened = manager().openExistingProject(project.rootUri).getOrNull()

        assertThat(reopened?.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
    }

    // ------------------------------------------------------------- partial failure

    @Test
    fun `a partial template failure is reported rather than swallowed`() = runTest {
        // Honest failure is the requirement: a half-written project must surface
        // as a failure with a step, never as a Success with missing files.
        fileSystem.addNode(uri = pickedParent, name = "Documents", isDirectory = true)
        fileSystem.failCreateFile("MainActivity.kt")

        val result = manager().createProject(pickedParent, "MyApp")

        val error = (result as AppResult.Failure).error
        assertThat(error.step).isNotNull()
        // No project row is registered for a project that was not finished.
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `a partial template failure leaves the created directories in place`() = runTest {
        // Phase 2 has no delete, so rolling back is not available. Leaving a
        // visible partial tree is the recoverable outcome, and the failure must
        // say so rather than pretending the project opened.
        fileSystem.addNode(uri = pickedParent, name = "Documents", isDirectory = true)
        fileSystem.failCreateFile("MainActivity.kt")

        val result = manager().createProject(pickedParent, "MyApp")

        val projectDir = fileSystem.listDirectory(pickedParent).getOrNull().orEmpty()
            .first { it.name == "MyApp" }
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(fileSystem.listDirectory(projectDir.uri).getOrNull()).isNotEmpty()
    }

    @Test
    fun `a failure is reported when the project folder name is taken by a file`() = runTest {
        // Reachable on the device: the user picks a parent that already holds a
        // *file* called MyApp. Creating the project root must fail with a step
        // and register nothing, not quietly pick a different name.
        fileSystem.addNode(uri = pickedParent, name = "Documents", isDirectory = true)
        fileSystem.addNode(
            uri = "${pickedParent}/document/file:MyApp",
            name = "MyApp",
            isDirectory = false,
        )

        val result = manager().createProject(pickedParent, "MyApp")

        val error = (result as AppResult.Failure).error
        assertThat(error.step).isNotNull()
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `a template directory occupied by a file is reported, not skipped`() = runTest {
        // Reachable when a project folder is reused: the walk finds "app/src" as
        // a file where it needs a directory, and must say so rather than carry on
        // and produce a tree missing every file underneath it.
        val projectDir = fileSystem.seedDirectory(pickedParent, "MyApp")
        fileSystem.seedPath(projectDir, "app", isDirectory = true)
        fileSystem.seedPath(projectDir, "app/src", isDirectory = false)

        val result = manager().writeTemplate(projectDir)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.step)
            .isEqualTo(ProjectManager.STEP_TEMPLATE_DIR_CONFLICT)
        // The tell-tale of the old bug: the manager went on to "finish" a project
        // whose tree was missing the required files.
        assertThat(walkTree(projectDir).any { it.startsWith("app/src/main") }).isFalse()
    }

    // ------------------------------------------------- create then re-open identity

    @Test
    fun `re-opening a created project does not create a duplicate row`() = runTest {
        // A created project is stored as a tree-based document URI; the same
        // folder re-picked by the user comes back as a bare tree URI. Comparing
        // the strings made one folder two project rows.
        val created = create()
        assertThat(repository.all()).hasSize(1)

        val rePicked = "content://auth/tree/MyApp"
        val reopened = manager().openExistingProject(rePicked).getOrNull()

        assertThat(reopened?.id).isEqualTo(created.id)
        assertThat(repository.all()).hasSize(1)
    }

    @Test
    fun `a folder re-picked as a bare tree uri does not duplicate a stored document uri`() =
        runTest {
            // The real-provider shape, seeded directly so it does not depend on
            // how the fake names created children. A project this app created is
            // stored as `…/tree/<parent>/document/primary:MyApp`; the same folder
            // arrives from the picker as `…/tree/primary:MyApp`.
            val stored = repository.seed(
                name = "MyApp",
                rootUri = "content://auth/tree/primary%3ADocuments/document/primary%3AMyApp",
            )
            val rePicked = "content://auth/tree/primary%3AMyApp"
            fileSystem.addNode(uri = rePicked, name = "MyApp", isDirectory = true)

            val reopened = manager().openExistingProject(rePicked).getOrNull()

            assertThat(reopened?.id).isEqualTo(stored.id)
            assertThat(repository.all()).hasSize(1)
        }

    @Test
    fun `a nested project is not merged with its parent project`() = runTest {
        // One is `primary:MyApp`, the other `primary:MyApp/app`. The parent id is
        // a prefix of the child id, so a prefix comparison would collapse them.
        repository.seed("MyApp", "content://auth/tree/primary%3ADocuments/document/primary%3AMyApp")
        fileSystem.addNode(
            uri = "content://auth/tree/primary%3AMyApp/document/primary%3AMyApp%2Fapp",
            name = "app",
            isDirectory = true,
        )

        val nested = manager()
            .openExistingProject("content://auth/tree/primary%3AMyApp/document/primary%3AMyApp%2Fapp")
            .getOrNull()

        assertThat(nested?.name).isEqualTo("app")
        assertThat(repository.all()).hasSize(2)
    }

    @Test
    fun `re-opening by the identical uri is still idempotent`() = runTest {
        val created = create()

        val again = manager().openExistingProject(created.rootUri).getOrNull()

        assertThat(again?.id).isEqualTo(created.id)
        assertThat(repository.all()).hasSize(1)
    }

    @Test
    fun `two different folders stay two projects`() = runTest {
        // Guards the folder-identity fix from over-matching.
        create("MyApp")
        val otherParent = "content://auth/tree/primary%3AOther"
        fileSystem.addNode(uri = otherParent, name = "Other", isDirectory = true)
        manager().createProject(otherParent, "Other")

        assertThat(repository.all()).hasSize(2)
    }

    @Test
    fun `stored root uri stays a tree based document uri`() = runTest {
        // Rewriting it to a re-rooted tree URI would put it outside the prefix
        // the persisted grant covers, so traversal is what must be preserved.
        val project = create()

        assertThat(SafUri.isDocumentBased(project.rootUri)).isTrue()
        assertThat(SafUri.hasTree(project.rootUri)).isTrue()
    }

    @Test
    fun `the explorer can navigate from the root down to MainActivity`() = runTest {
        // The exact walk the Explorer performs when a user taps into app/src.
        val project = create()

        var current = project.rootUri
        for (segment in listOf("app", "src", "main", "java", "com", "nexg", "template")) {
            val child = fileSystem.findChild(current, segment).getOrNull()
            assertThat(child).isNotNull()
            assertThat(child!!.isDirectory).isTrue()
            current = child.uri
        }
        val activity = fileSystem.findChild(current, "MainActivity.kt").getOrNull()
        assertThat(activity).isNotNull()
        assertThat(activity!!.isDirectory).isFalse()
        assertThat(fileSystem.readText(activity.uri).getOrNull())
            .contains("class MainActivity")
    }
}
