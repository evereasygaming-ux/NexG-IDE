package com.nexg.ide

import android.app.Application
import com.nexg.ide.application.EditorManager
import com.nexg.ide.application.FileManager
import com.nexg.ide.application.ProjectManager
import com.nexg.ide.core.dispatch.DefaultDispatcherProvider
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.log.LogSink
import com.nexg.ide.core.log.LogcatSink
import com.nexg.ide.data.db.NexGDatabase
import com.nexg.ide.data.local.SecurePrefs
import com.nexg.ide.data.repo.RoomFileRepository
import com.nexg.ide.data.repo.RoomProjectRepository
import com.nexg.ide.domain.port.AiBackend
import com.nexg.ide.domain.port.CredentialStore
import com.nexg.ide.domain.port.DeveloperToolPort
import com.nexg.ide.domain.port.FileSystemPort
import com.nexg.ide.integration.atlassian.AtlassianCliAdapter
import com.nexg.ide.integration.gemini.GeminiBackend
import com.nexg.ide.integration.saf.SafFsAdapter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency-injection container (PLAN.MD 4.5: manual DI via
 * `AppContainer`; Hilt and Dagger are forbidden for this project).
 *
 * Phase 2 wires the real seams: the filesystem port, the two repositories and
 * the two application managers. The port has exactly one implementation
 * ([SafFsAdapter]) but it is a genuine boundary, not ceremony — it is what lets
 * `ProjectManager` and `FileManager` be tested without a device.
 *
 * Everything is created eagerly and in dependency order. Phase 2 does not need
 * laziness; the objects are cheap and building the Room instance lazily would
 * only move the failure to first use.
 */
class AppContainer(
    val logSink: LogSink,
    val logger: AppLogger,
    val dispatchers: DispatcherProvider,
    val fileSystem: FileSystemPort,
    val projectManager: ProjectManager,
    val fileManager: FileManager,
    val editorManager: EditorManager,
    val credentials: CredentialStore,
    val aiBackend: AiBackend,
    val developerTools: DeveloperToolPort,
)

/**
 * `open` is load-bearing here, not decoration: [createContainer] is documented as
 * the seam a test overrides to inject an in-memory log sink, and a `protected open`
 * member on a final class cannot be overridden at all.
 */
open class NexGApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = createContainer(this)
        container.logger.i(LogCategory.APP, "NexGApp created")
    }

    /**
     * Factory seam, open so a test can install an in-memory log sink and assert
     * on output without logcat.
     */
    protected open fun createContainer(app: Application): AppContainer {
        val sink = LogcatSink()
        val logger = AppLogger(
            sink = sink,
            minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
        )
        val dispatchers = DefaultDispatcherProvider()

        val database = NexGDatabase.build(app)
        val projectRepository = RoomProjectRepository(database.projectDao())
        val fileRepository = RoomFileRepository(database.recentFileDao())
        val fileSystem: FileSystemPort = SafFsAdapter(app.contentResolver, logger)

        // Phase 4: the AI + developer-tool boundary. OkHttp is the dedicated
        // client; timeouts are explicit because a streaming SSE response that
        // stalls is the one failure mode that would otherwise hang the chat.
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val credentials: CredentialStore = SecurePrefs(app)

        return AppContainer(
            logSink = sink,
            logger = logger,
            dispatchers = dispatchers,
            fileSystem = fileSystem,
            projectManager = ProjectManager(
                fileSystem = fileSystem,
                projects = projectRepository,
                dispatchers = dispatchers,
                logger = logger,
            ),
            fileManager = FileManager(
                fileSystem = fileSystem,
                recentFiles = fileRepository,
                dispatchers = dispatchers,
                logger = logger,
            ),
            // Shares the same filesystem port as the two managers above, so a
            // file the Explorer lists is the file the editor opens and saves.
            // A separate adapter instance would still address the same document,
            // but sharing one keeps the grant handling in one place.
            editorManager = EditorManager(
                fileSystem = fileSystem,
                files = fileRepository,
                dispatchers = dispatchers,
                logger = logger,
            ),
            credentials = credentials,
            aiBackend = GeminiBackend(
                client = httpClient,
                credentials = credentials,
                logger = logger,
                dispatchers = dispatchers,
            ),
            // No executor is wired until the Termux executor phase ships, which
            // is a real state — AtlassianCliAdapter reports the tool as
            // unavailable and must never fake a shell. The consumer of this
            // field only ever calls `health()` and `run()`, both of which
            // degrade to "unavailable" for this wiring.
            developerTools = AtlassianCliAdapter(executor = null, logger = logger),
        )
    }

    companion object {
        @Volatile
        private var instance: NexGApp? = null

        fun container(): AppContainer =
            requireNotNull(instance) { "NexGApp not initialised" }.container
    }
}
