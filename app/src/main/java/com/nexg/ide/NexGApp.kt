package com.nexg.ide

import android.app.Application
import com.nexg.ide.core.dispatch.DefaultDispatcherProvider
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.log.LogSink
import com.nexg.ide.core.log.LogcatSink

/**
 * Manual dependency-injection container (PLAN.MD 4.5, "manual DI via
 * AppContainer"; no Hilt, no Dagger, no annotation processing).
 *
 * Deliberately tiny in Phase 1: it holds only what actually exists. Interfaces
 * are not declared ahead of their implementations — an interface with one
 * implementation and no second implementation is not a seam, it is ceremony.
 * When a real boundary appears (network, filesystem, project host) it is
 * declared then and wired here.
 */
class AppContainer(
    val logSink: LogSink,
    val logger: AppLogger,
    val dispatchers: DispatcherProvider,
)

class NexGApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = createContainer()
        container.logger.i(LogCategory.APP, "NexGApp created")
    }

    /**
     * Factory seam, open so a test can install an in-memory log sink and assert
     * on output without logcat.
     */
    protected open fun createContainer(): AppContainer {
        val sink = LogcatSink()
        return AppContainer(
            logSink = sink,
            logger = AppLogger(
                sink = sink,
                // Verbose in debug; raise for release. Phase 1 sets no secrets
                // and makes no network calls, so this is the only sink in use.
                minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
            ),
            dispatchers = DefaultDispatcherProvider(),
        )
    }

    companion object {
        @Volatile
        private var instance: NexGApp? = null

        fun container(): AppContainer =
            requireNotNull(instance) { "NexGApp not initialised" }.container
    }
}
